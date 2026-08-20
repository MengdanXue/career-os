package com.careeros.application;

public final class DecisionExceptions {
    private DecisionExceptions() {}
    public static final class CandidateNotFoundException extends RuntimeException { public CandidateNotFoundException(String message) { super(message); } }
    public static final class JobNotFoundException extends RuntimeException { public JobNotFoundException(String message) { super(message); } }
    public static final class JobNotAdmittedException extends RuntimeException { public JobNotAdmittedException(String message) { super(message); } }
    public static final class DecisionNotFoundException extends RuntimeException { public DecisionNotFoundException(String message) { super(message); } }
}
