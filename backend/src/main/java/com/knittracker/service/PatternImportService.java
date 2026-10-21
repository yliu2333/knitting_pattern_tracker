package com.knittracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.dao.PatternDao;
import com.knittracker.dao.TechniqueDao;
import com.knittracker.ir.IrValidator;
import com.knittracker.ir.PatternIr;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.SegmentIr;
import com.knittracker.ir.StepIr;
import com.knittracker.security.UserScope;
import com.knittracker.service.count.PatternPlan;
import com.knittracker.service.count.StitchSimulator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Validates and simulates an IR pattern, then stores it (with computed stitch counts) for one user. Used by fixtures now and by the LLM parser later. */
@Service
public class PatternImportService {

    private final PatternDao dao;
    private final UserScope userScope;
    private final ObjectMapper mapper;
    private final TechniqueDao techniques;

    public PatternImportService(PatternDao dao, UserScope userScope, ObjectMapper mapper, TechniqueDao techniques) {
        this.techniques = techniques;
        this.dao = dao;
        this.userScope = userScope;
        this.mapper = mapper;
    }

    public UUID importPattern(UUID userId, PatternIr ir, String fileHash, String parserVersion) {
        List<String> errors = IrValidator.validate(ir);
        if (!errors.isEmpty()) throw new InvalidIrException(errors);
        String gauge = toJson(ir.gauge());
        return userScope.call(userId, () -> {
            PatternPlan plan;
            try {
                plan = StitchSimulator.simulate(ir, techniques.load());
            } catch (StitchSimulator.SimulationException e) {
                throw new InvalidIrException(List.of(e.getMessage()));
            }
            UUID patternId = dao.insertPattern(userId, ir, fileHash, parserVersion, gauge);
            for (int s = 0; s < ir.sections().size(); s++) {
                SectionIr sec = ir.sections().get(s);
                UUID sectionId = dao.insertSection(userId, patternId, s, sec.nameKey(), sec.nameSrc());
                for (int t = 0; t < sec.steps().size(); t++) {
                    StepIr step = sec.steps().get(t);
                    PatternPlan.StepPlan sp = plan.sections().get(s).get(t);
                    UUID stepId = dao.insertStep(userId, sectionId, t, step, sp.startSts(), sp.endSts());
                    for (int r = 0; r < step.rows().size(); r++) {
                        RowIr row = step.rows().get(r);
                        UUID rowId = dao.insertRow(userId, stepId, r, row);
                        insertSegments(userId, rowId, null, row.segments());
                    }
                }
            }
            return patternId;
        });
    }

    private void insertSegments(UUID userId, UUID rowId, UUID parentId, List<SegmentIr> segments) {
        for (int i = 0; i < segments.size(); i++) {
            SegmentIr g = segments.get(i);
            UUID id = dao.insertSegment(userId, rowId, parentId, i, g);
            insertSegments(userId, rowId, id, g.childrenOrEmpty());
        }
    }

    private String toJson(Object o) {
        try {
            return o == null ? null : mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class InvalidIrException extends RuntimeException {
        private final List<String> errors;

        public InvalidIrException(List<String> errors) {
            super("Invalid pattern IR: " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
