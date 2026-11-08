package com.whale.crm.ai.application;

public class AiWorkerException extends RuntimeException {
    private final boolean terminal;

    public AiWorkerException(String message, boolean terminal) {
        super(message);
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
