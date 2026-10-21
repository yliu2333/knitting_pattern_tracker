package com.knittracker.service.technique;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class TechniqueLibraryTest {

    final TechniqueLibrary lib = TechniqueLibrary.builtin();

    @Test
    void aliasNormalisationIgnoresCaseWidthAndSeparators() {
        assertThat(AliasNorm.of("K2tog")).isEqualTo(AliasNorm.of("ｋ２ｔｏｇ")).isEqualTo(AliasNorm.of("k2 - tog"));
        assertThat(lib.byAlias("左上2目一度")).map(Technique::code).contains("K2TOG");
        assertThat(lib.byAlias("下针2并1")).map(Technique::code).contains("K2TOG");
        assertThat(lib.byAlias("M1-L")).map(Technique::code).contains("M1L");
    }

    @Test
    void builtinHasNoNameMappedToTwoTechniques() throws Exception {
        JsonNode root = new ObjectMapper().readTree(getClass().getClassLoader()
                .getResourceAsStream(TechniqueLibrary.BUILTIN_RESOURCE));
        Map<String, String> owner = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (JsonNode t : root.get("techniques")) {
            String code = t.get("code").asText();
            List<String> names = new ArrayList<>(List.of(code));
            t.path("names").forEach(n -> names.add(n.asText()));
            t.path("aliases").forEach(list -> list.forEach(a -> names.add(a.asText())));
            for (String n : names) {
                String prev = owner.putIfAbsent(AliasNorm.of(n), code);
                if (prev != null && !prev.equals(code)) clashes.add(n + " → " + prev + " and " + code);
            }
        }
        assertThat(clashes).isEmpty();
    }

    @Test
    void builtinHasNoTwoTechniquesWithTheSameStructure() {
        List<String> sigs = lib.all().stream().map(Technique::signature).filter(Objects::nonNull).toList();
        assertThat(sigs).doesNotHaveDuplicates();
    }

    @Test
    void everyTechniqueRendersInChineseAndEnglish() {
        for (Technique t : lib.all()) {
            assertThat(lib.template(t.code(), "zh")).as(t.code()).isNotBlank();
            assertThat(lib.template(t.code(), "en")).as(t.code()).isNotBlank();
        }
    }

    @Test
    void sameStructureUnderAnotherNameIsFoundBySignature() {
        Technique k2tog = lib.byCode("K2TOG").orElseThrow();
        assertThat(lib.bySignature(Signature.of(Technique.Kind.STITCH, k2tog.def()))).contains(k2tog);
    }
}
