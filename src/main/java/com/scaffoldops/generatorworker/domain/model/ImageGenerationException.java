package com.scaffoldops.generatorworker.domain.model;

public class ImageGenerationException extends IllegalStateException {
    private final String failureStage;
    private final boolean retryable;

    public ImageGenerationException(String failureStage, String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.failureStage = failureStage;
        this.retryable = retryable;
    }

    public String failureStage() { return failureStage; }
    public boolean retryable() { return retryable; }
}
