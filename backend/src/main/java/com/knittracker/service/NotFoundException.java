package com.knittracker.service;

/** Missing OR owned by someone else: callers must not be able to tell the difference. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String what) {
        super(what + " not found");
    }
}
