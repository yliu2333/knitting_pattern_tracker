package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.service.technique.AliasNorm;
import com.knittracker.service.technique.Signature;
import com.knittracker.service.technique.Technique;
import com.knittracker.service.technique.TechniqueLibrary;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Seeds techniques/builtin.json into technique, technique_alias and technique_i18n. Repeatable: Flyway re-runs
 * it whenever the file's checksum changes, so builtin.json is the single source of truth for shipped techniques.
 */
public class R__Builtin_techniques extends BaseJavaMigration {

    @Override
    public Integer getChecksum() {
        CRC32 crc = new CRC32();
        crc.update(read());
        return (int) crc.getValue();
    }

    @Override
    public void migrate(Context context) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(read());
        Connection c = context.getConnection();
        try (PreparedStatement tech = c.prepareStatement("""
                     INSERT INTO technique (code, kind, def, signature, origin, names)
                     VALUES (?, ?, CAST(? AS jsonb), ?, 'BUILTIN', CAST(? AS jsonb))
                     ON CONFLICT (code) DO UPDATE SET kind = EXCLUDED.kind, def = EXCLUDED.def,
                         signature = EXCLUDED.signature, names = EXCLUDED.names""");
             PreparedStatement alias = c.prepareStatement("""
                     INSERT INTO technique_alias (alias_norm, alias, lang, code) VALUES (?, ?, ?, ?)
                     ON CONFLICT (alias_norm) DO NOTHING""");
             PreparedStatement i18n = c.prepareStatement("""
                     INSERT INTO technique_i18n (code, lang, name, template) VALUES (?, ?, ?, ?)
                     ON CONFLICT (code, lang) DO UPDATE SET name = EXCLUDED.name, template = EXCLUDED.template""")) {
            for (JsonNode n : root.get("techniques")) {
                Technique t = TechniqueLibrary.parse(mapper, n, Technique.Origin.BUILTIN);
                tech.setString(1, t.code());
                tech.setString(2, t.kind().name());
                tech.setString(3, t.def() == null ? null : t.def().toString());
                tech.setString(4, Signature.of(t.kind(), t.def()));
                tech.setString(5, mapper.writeValueAsString(t.names()));
                tech.executeUpdate();
                for (Map.Entry<String, java.util.List<String>> e : t.aliases().entrySet()) {
                    for (String a : e.getValue()) addAlias(alias, a, e.getKey(), t.code());
                }
                for (Map.Entry<String, String> e : t.names().entrySet()) addAlias(alias, e.getValue(), e.getKey(), t.code());
                for (Map.Entry<String, String> e : t.templates().entrySet()) {
                    i18n.setString(1, t.code());
                    i18n.setString(2, e.getKey());
                    i18n.setString(3, t.names().getOrDefault(e.getKey(), t.code()));
                    i18n.setString(4, e.getValue());
                    i18n.executeUpdate();
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> langs = root.path("renderKeys").fields(); langs.hasNext(); ) {
                Map.Entry<String, JsonNode> lang = langs.next();
                for (Iterator<Map.Entry<String, JsonNode>> keys = lang.getValue().fields(); keys.hasNext(); ) {
                    Map.Entry<String, JsonNode> k = keys.next();
                    i18n.setString(1, k.getKey());
                    i18n.setString(2, lang.getKey());
                    i18n.setString(3, k.getKey());
                    i18n.setString(4, k.getValue().asText());
                    i18n.executeUpdate();
                }
            }
        }
    }

    private static void addAlias(PreparedStatement ps, String alias, String lang, String code) throws Exception {
        String norm = AliasNorm.of(alias);
        if (norm.isEmpty()) return;
        ps.setString(1, norm);
        ps.setString(2, alias);
        ps.setString(3, lang);
        ps.setString(4, code);
        ps.executeUpdate();
    }

    private static byte[] read() {
        try (InputStream in = R__Builtin_techniques.class.getClassLoader()
                .getResourceAsStream(TechniqueLibrary.BUILTIN_RESOURCE)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
