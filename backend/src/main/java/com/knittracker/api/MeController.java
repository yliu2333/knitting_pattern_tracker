package com.knittracker.api;

import com.knittracker.dao.AppUserDao;
import com.knittracker.dao.AppUserDao.Me;
import com.knittracker.dao.GlossaryDao;
import com.knittracker.security.UserScope;
import com.knittracker.service.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own account. DELETE is required by App Store rules for apps with Sign in with Apple. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    public record UpdateMeRequest(@NotBlank String uiLang) {}

    private final AppUserDao users;
    private final GlossaryDao glossaries;
    private final UserScope scope;

    public MeController(AppUserDao users, GlossaryDao glossaries, UserScope scope) {
        this.users = users;
        this.glossaries = glossaries;
        this.scope = scope;
    }

    @GetMapping
    public Me get() {
        return scope.asCurrentUser(() -> users.me().orElseThrow(() -> new NotFoundException("user")));
    }

    @PatchMapping
    public Me update(@Valid @RequestBody UpdateMeRequest req) {
        if (glossaries.load(req.uiLang()).templates().isEmpty()) {
            throw new IllegalArgumentException("unsupported language: " + req.uiLang());
        }
        return scope.asCurrentUser(() -> {
            users.setUiLang(req.uiLang());
            return users.me().orElseThrow(() -> new NotFoundException("user"));
        });
    }

    /** Deletes the account and all its patterns, projects, progress and tokens. Irreversible. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        int deleted = scope.asCurrentUser(users::deleteMe);
        if (deleted == 0) throw new NotFoundException("user");
    }
}
