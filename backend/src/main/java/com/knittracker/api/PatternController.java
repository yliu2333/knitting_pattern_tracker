package com.knittracker.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.dao.PatternReadDao;
import com.knittracker.dao.PatternReadDao.PatternSummary;
import com.knittracker.ir.PatternIr;
import com.knittracker.security.CurrentUser;
import com.knittracker.security.UserScope;
import com.knittracker.service.PatternImportService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Patterns of the caller. For now IR is uploaded directly; milestone 6 adds file upload + LLM parse. */
@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    static final String MANUAL_IR = "manual-ir-v1";

    private final PatternImportService importer;
    private final PatternReadDao patterns;
    private final UserScope scope;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    public PatternController(PatternImportService importer, PatternReadDao patterns, UserScope scope,
                             CurrentUser currentUser, ObjectMapper mapper) {
        this.importer = importer;
        this.patterns = patterns;
        this.scope = scope;
        this.currentUser = currentUser;
        this.mapper = mapper;
    }

    /** Same IR uploaded twice by the same user returns the existing pattern (200), else 201. */
    @PostMapping
    public ResponseEntity<Map<String, UUID>> upload(@RequestBody PatternIr ir) {
        String hash = sha256(ir);
        Optional<UUID> existing = scope.asCurrentUser(() -> patterns.findByHash(hash, MANUAL_IR));
        if (existing.isPresent()) return ResponseEntity.ok(Map.of("id", existing.get()));
        UUID id = importer.importPattern(currentUser.id(), ir, hash, MANUAL_IR);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @GetMapping
    public List<PatternSummary> list() {
        return scope.asCurrentUser(patterns::list);
    }

    private String sha256(PatternIr ir) {
        try {
            byte[] json = mapper.writeValueAsString(ir).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
