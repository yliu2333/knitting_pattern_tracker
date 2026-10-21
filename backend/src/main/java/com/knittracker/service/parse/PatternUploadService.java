package com.knittracker.service.parse;

import com.knittracker.dao.PatternReadDao;
import com.knittracker.dao.TechniqueDao;
import com.knittracker.service.technique.TechniqueLibrary;
import com.knittracker.ir.PatternIr.Craft;
import com.knittracker.ir.PatternIr.SourceType;
import com.knittracker.security.CurrentUser;
import com.knittracker.security.UserScope;
import com.knittracker.service.PatternImportService;
import com.knittracker.service.parse.PatternParser.ParseOptions;
import com.knittracker.service.parse.PatternParser.SourceFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Uploaded file → parsed, stored pattern. Parse cache key = (user, file hash incl. size, parser version):
 * the same user re-uploading the same file costs no LLM call; other users never reuse it.
 */
@Service
public class PatternUploadService {

    public record UploadResult(UUID patternId, boolean created) {}

    private final List<PatternParser> parsers;
    private final PatternImportService importer;
    private final PatternReadDao patterns;
    private final UserScope scope;
    private final CurrentUser currentUser;
    private final TechniqueDao techniques;

    public PatternUploadService(List<PatternParser> parsers, PatternImportService importer, PatternReadDao patterns,
                                UserScope scope, CurrentUser currentUser, TechniqueDao techniques) {
        this.techniques = techniques;
        this.parsers = parsers;
        this.importer = importer;
        this.patterns = patterns;
        this.scope = scope;
        this.currentUser = currentUser;
    }

    public UploadResult upload(SourceFile file, String size) {
        SourceType type = sourceType(file.mimeType());
        PatternParser parser = parsers.stream().filter(p -> p.supports(type, Craft.KNIT)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no parser for " + type));
        String hash = hash(file.data(), size);
        Optional<UUID> cached = scope.asCurrentUser(() -> patterns.findByHash(hash, parser.version()));
        if (cached.isPresent()) return new UploadResult(cached.get(), false);

        TechniqueLibrary library = scope.asCurrentUser(techniques::load);
        // LLM calls happen outside any transaction; what they produced is stored in one transaction below.
        PatternParser.ParsedPattern parsed = parser.parse(file, type, new ParseOptions(size), library);
        UUID userId = currentUser.id();
        boolean verified = statesCounts(parsed.ir());
        UUID id = scope.call(userId, () -> {
            techniques.saveLearned(userId, parsed.learned(), parsed.aliases(), verified);
            return importer.importPattern(userId, parsed.ir(), hash, parser.version());
        });
        if (!parsed.learned().isEmpty() || !parsed.aliases().isEmpty()) scope.call(userId, techniques::promote);
        return new UploadResult(id, true);
    }

    /** The pattern states stitch counts the simulator checked, so techniques it taught are verified by it. */
    static boolean statesCounts(com.knittracker.ir.PatternIr ir) {
        return ir.sections().stream().flatMap(s -> s.steps().stream()).anyMatch(st -> st.statedEndSts() != null
                || st.rows().stream().anyMatch(r -> r.segments().stream()
                        .anyMatch(g -> "VERIFY_COUNT".equals(g.code()) && g.count() != null)));
    }

    static SourceType sourceType(String mimeType) {
        if (mimeType == null) throw new IllegalArgumentException("missing content type");
        if (mimeType.equals("application/pdf") || mimeType.startsWith("text/")) return SourceType.TEXT_PDF;
        if (mimeType.startsWith("image/")) return SourceType.TEXT_IMAGE;
        throw new IllegalArgumentException("unsupported file type: " + mimeType);
    }

    static String hash(byte[] data, String size) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data);
            md.update((byte) 0);
            md.update((size == null ? "" : size.strip()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
