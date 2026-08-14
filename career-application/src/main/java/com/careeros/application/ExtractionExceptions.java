package com.careeros.application;

public final class ExtractionExceptions {
    private ExtractionExceptions() {}

    public static final class ExtractionNotFoundException extends RuntimeException {
        public ExtractionNotFoundException(String detail) { super(detail); }
    }
    public static final class ReviewNotFoundException extends RuntimeException {
        public ReviewNotFoundException(String detail) { super(detail); }
    }
    public static final class ReviewConflictException extends RuntimeException {
        public ReviewConflictException(String detail) { super(detail); }
    }
    public static final class UnsupportedDocumentException extends RuntimeException {
        public UnsupportedDocumentException(String detail) { super(detail); }
    }
    public static final class DocumentTooLargeException extends RuntimeException {
        public DocumentTooLargeException(String detail) { super(detail); }
    }
    public static final class InvalidProposalException extends RuntimeException {
        public InvalidProposalException(String detail) { super(detail); }
    }
    public static final class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String detail) { super(detail); }
    }
}
