package com.knittracker.service.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knittracker.dao.TechniqueDao;
import com.knittracker.service.technique.TechniqueLibrary;
import com.knittracker.dao.PatternReadDao;
import com.knittracker.dao.PatternReadDao.StoredPattern;
import com.knittracker.dao.ProjectDao;
import com.knittracker.dao.ProjectDao.ProjectRow;
import com.knittracker.ir.RowIr;
import com.knittracker.ir.SectionIr;
import com.knittracker.ir.StepIr;
import com.knittracker.security.CurrentUser;
import com.knittracker.security.UserScope;
import com.knittracker.service.ConflictException;
import com.knittracker.service.NotFoundException;
import com.knittracker.service.count.PatternPlan;
import com.knittracker.service.count.PatternPlan.RoundPlan;
import com.knittracker.service.count.StitchSimulator;
import com.knittracker.service.progress.ProgressRules.Change;
import com.knittracker.service.progress.TrackerView.LastAction;
import com.knittracker.service.progress.TrackerView.RoundView;
import com.knittracker.service.progress.TrackerView.SectionView;
import com.knittracker.service.progress.TrackerView.StepView;
import com.knittracker.service.render.Glossary;
import com.knittracker.service.render.InstructionRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Projects and their progress. Every public method runs as the authenticated caller under RLS. */
@Service
public class ProjectService {

    /** Undo history entry: what was done and the step states before it. */
    record ProgressEvent(String type, UUID stepId, Integer round, List<StepState> before) {}

    private final UserScope scope;
    private final CurrentUser currentUser;
    private final ProjectDao projects;
    private final PatternReadDao patterns;
    private final TechniqueDao techniques;
    private final ObjectMapper mapper;

    public ProjectService(UserScope scope, CurrentUser currentUser, ProjectDao projects, PatternReadDao patterns,
                          TechniqueDao techniques, ObjectMapper mapper) {
        this.techniques = techniques;
        this.scope = scope;
        this.currentUser = currentUser;
        this.projects = projects;
        this.patterns = patterns;
        this.mapper = mapper;
    }

    public TrackerView create(UUID patternId) {
        return scope.asCurrentUser(() -> {
            if (patterns.load(patternId).isEmpty()) throw new NotFoundException("pattern");
            return view(projects.create(currentUser.id(), patternId));
        });
    }

    public List<ProjectRow> list() {
        return scope.asCurrentUser(projects::list);
    }

    public TrackerView get(UUID projectId) {
        return scope.asCurrentUser(() -> view(projectId));
    }

    public TrackerView advance(UUID projectId, UUID stepId) {
        return act(projectId, "ADVANCE", stepId, null, r -> r.advance(stepId));
    }

    public TrackerView retreat(UUID projectId, UUID stepId) {
        return act(projectId, "RETREAT", stepId, null, r -> r.retreat(stepId));
    }

    public TrackerView finish(UUID projectId, UUID stepId) {
        return act(projectId, "FINISH", stepId, null, r -> r.finish(stepId));
    }

    public TrackerView rewind(UUID projectId, UUID stepId, int round) {
        return act(projectId, "REWIND", stepId, round, r -> r.rewind(stepId, round));
    }

    public TrackerView undo(UUID projectId) {
        return scope.asCurrentUser(() -> {
            ProjectRow p = projects.lock(projectId).orElseThrow(() -> new NotFoundException("project"));
            ProjectDao.EventRow ev = projects.lastActiveEvent(projectId)
                    .orElseThrow(() -> new ConflictException("nothing to undo"));
            ProgressRules rules = rules(p);
            save(projectId, rules.restore(read(ev.payload()).before()));
            projects.markUndone(ev.id());
            return view(projectId);
        });
    }

    public TrackerView note(UUID projectId, UUID stepId, String note) {
        return scope.asCurrentUser(() -> {
            ProjectRow p = projects.lock(projectId).orElseThrow(() -> new NotFoundException("project"));
            requireStep(p, stepId);
            projects.saveNote(currentUser.id(), projectId, stepId, note == null || note.isBlank() ? null : note);
            return view(projectId);
        });
    }

    public RoundView round(UUID projectId, UUID stepId, int round, String lang) {
        return scope.asCurrentUser(() -> {
            ProjectRow p = projects.find(projectId).orElseThrow(() -> new NotFoundException("project"));
            StoredPattern stored = patterns.load(p.patternId()).orElseThrow(() -> new NotFoundException("pattern"));
            TechniqueLibrary library = techniques.load();
            PatternPlan plan = StitchSimulator.simulate(stored.ir(), library);
            int[] at = locate(stored, stepId);
            StepIr st = stored.ir().sections().get(at[0]).steps().get(at[1]);
            List<RoundPlan> rounds = plan.sections().get(at[0]).get(at[1]).rounds();
            if (round < 0 || round >= rounds.size()) throw new NotFoundException("round");
            RoundPlan rp = rounds.get(round);
            RowIr row = st.rows().get(rp.rowOrd());
            if (!library.supportsLanguage(lang)) throw new IllegalArgumentException("unsupported language: " + lang);
            Glossary g = Glossary.from(library, lang);
            return new RoundView(stepId, round, st.kind() == StepIr.Kind.CHECKPOINT ? Integer.valueOf(1) : st.total(),
                    row.nameSrc(), row.side() == null ? null : row.side().name(),
                    InstructionRenderer.render(rp.segments(), g), rp.stsAfter(), rp.sectionsAtStart(), st.noteSrc());
        });
    }

    // ---------- internals (always inside a UserScope transaction) ----------

    private TrackerView act(UUID projectId, String type, UUID stepId, Integer round,
                            Function<ProgressRules, List<Change>> action) {
        return scope.asCurrentUser(() -> {
            ProjectRow p = projects.lock(projectId).orElseThrow(() -> new NotFoundException("project"));
            List<Change> changes = action.apply(rules(p));
            save(projectId, changes);
            projects.addEvent(currentUser.id(), projectId,
                    write(new ProgressEvent(type, stepId, round, changes.stream().map(Change::before).toList())));
            return view(projectId);
        });
    }

    private void save(UUID projectId, List<Change> changes) {
        for (Change c : changes) projects.saveState(currentUser.id(), projectId, c.after());
    }

    private ProgressRules rules(ProjectRow p) {
        return new ProgressRules(projects.stepMetas(p.patternId()), projects.progress(p.id()));
    }

    private void requireStep(ProjectRow p, UUID stepId) {
        if (projects.stepMetas(p.patternId()).stream().noneMatch(m -> m.id().equals(stepId))) {
            throw new NotFoundException("step");
        }
    }

    private TrackerView view(UUID projectId) {
        ProjectRow p = projects.find(projectId).orElseThrow(() -> new NotFoundException("project"));
        StoredPattern stored = patterns.load(p.patternId()).orElseThrow(() -> new NotFoundException("pattern"));
        PatternPlan plan = StitchSimulator.simulate(stored.ir(), techniques.load());
        ProgressRules rules = rules(p);
        Map<UUID, String> notes = projects.notes(projectId);
        Map<UUID, ProgressRules.StepMeta> metas = new java.util.HashMap<>();
        projects.stepMetas(p.patternId()).forEach(m -> metas.put(m.id(), m));

        List<SectionView> sections = new ArrayList<>();
        int total = 0;
        for (int s = 0; s < stored.ir().sections().size(); s++) {
            SectionIr sec = stored.ir().sections().get(s);
            List<StepView> steps = new ArrayList<>();
            for (int t = 0; t < sec.steps().size(); t++) {
                StepIr st = sec.steps().get(t);
                UUID id = stored.stepIds().get(s).get(t);
                PatternPlan.StepPlan sp = plan.sections().get(s).get(t);
                StepState state = rules.state(id);
                steps.add(new StepView(id, st.titleSrc(), st.kind().name(), st.unit() == null ? null : st.unit().name(),
                        metas.get(id).effectiveTotal(), st.groupCount(), st.rowsPerGroup(), st.needleMm(), st.target(),
                        sp.startSts(), sp.endSts(), state.done(), state.finished(), rules.isDone(metas.get(id)),
                        notes.get(id)));
                total++;
            }
            sections.add(new SectionView(sec.nameSrc(), steps));
        }
        LastAction last = projects.lastActiveEvent(projectId)
                .map(ev -> read(ev.payload()))
                .map(ev -> new LastAction(ev.type(), ev.stepId(), ev.round()))
                .orElse(null);
        return new TrackerView(projectId, p.patternId(), p.title(),
                rules.current().map(ProgressRules.StepMeta::id).orElse(null),
                rules.doneSteps(), total, rules.percent(), last, sections);
    }

    private static int[] locate(StoredPattern stored, UUID stepId) {
        for (int s = 0; s < stored.stepIds().size(); s++) {
            int t = stored.stepIds().get(s).indexOf(stepId);
            if (t >= 0) return new int[] {s, t};
        }
        throw new NotFoundException("step");
    }

    private String write(ProgressEvent e) {
        try {
            return mapper.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ProgressEvent read(String json) {
        try {
            return mapper.readValue(json, ProgressEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
