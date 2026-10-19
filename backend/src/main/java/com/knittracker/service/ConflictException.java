package com.knittracker.service;

/** The action is valid in general but not in the current progress state (e.g. +1 on a finished step). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
