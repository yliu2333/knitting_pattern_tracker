package com.knittracker.api;

import com.knittracker.dao.ProjectDao.ProjectRow;
import com.knittracker.service.progress.ProjectService;
import com.knittracker.service.progress.TrackerView;
import com.knittracker.service.progress.TrackerView.RoundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tracker API. No endpoint takes a user id: identity comes from the JWT only. */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    public record CreateProjectRequest(@NotNull UUID patternId) {}

    public record NoteRequest(@Size(max = 2000) String text) {}

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrackerView create(@Valid @RequestBody CreateProjectRequest req) {
        return projects.create(req.patternId());
    }

    @GetMapping
    public List<ProjectRow> list() {
        return projects.list();
    }

    @GetMapping("/{projectId}")
    public TrackerView get(@PathVariable UUID projectId) {
        return projects.get(projectId);
    }

    @GetMapping("/{projectId}/steps/{stepId}/rounds/{round}")
    public RoundView round(@PathVariable UUID projectId, @PathVariable UUID stepId, @PathVariable int round,
                           @RequestParam(defaultValue = "zh") String lang) {
        return projects.round(projectId, stepId, round, lang);
    }

    @PostMapping("/{projectId}/steps/{stepId}/advance")
    public TrackerView advance(@PathVariable UUID projectId, @PathVariable UUID stepId) {
        return projects.advance(projectId, stepId);
    }

    @PostMapping("/{projectId}/steps/{stepId}/retreat")
    public TrackerView retreat(@PathVariable UUID projectId, @PathVariable UUID stepId) {
        return projects.retreat(projectId, stepId);
    }

    @PostMapping("/{projectId}/steps/{stepId}/finish")
    public TrackerView finish(@PathVariable UUID projectId, @PathVariable UUID stepId) {
        return projects.finish(projectId, stepId);
    }

    /** 拆线: back to {@code round} (0-based) of this step; everything after it is cleared (undoable). */
    @PostMapping("/{projectId}/steps/{stepId}/rewind")
    public TrackerView rewind(@PathVariable UUID projectId, @PathVariable UUID stepId,
                              @RequestParam(defaultValue = "0") int round) {
        return projects.rewind(projectId, stepId, round);
    }

    @PutMapping("/{projectId}/steps/{stepId}/note")
    public TrackerView note(@PathVariable UUID projectId, @PathVariable UUID stepId,
                            @Valid @RequestBody NoteRequest req) {
        return projects.note(projectId, stepId, req.text());
    }

    @PostMapping("/{projectId}/undo")
    public TrackerView undo(@PathVariable UUID projectId) {
        return projects.undo(projectId);
    }
}
