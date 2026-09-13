package com.careeros;

import com.careeros.application.ExtractionExceptions;
import com.careeros.application.DecisionExceptions;
import com.careeros.application.CandidateProfileService;
import com.careeros.application.personal.CandidateDecisionDiffService;
import com.careeros.application.planning.CareerPlanService;
import java.net.URI;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", exception.getMessage());
    }
    @ExceptionHandler(ExtractionExceptions.UnsupportedDocumentException.class)
    ResponseEntity<ProblemDetail> unsupported(RuntimeException exception) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_DOCUMENT", "Unsupported document", exception.getMessage());
    }
    @ExceptionHandler(ExtractionExceptions.DocumentTooLargeException.class)
    ResponseEntity<ProblemDetail> tooLarge(RuntimeException exception) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_TOO_LARGE", "Document too large", exception.getMessage());
    }
    @ExceptionHandler(ExtractionExceptions.InvalidProposalException.class)
    ResponseEntity<ProblemDetail> invalidProposal(RuntimeException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROPOSAL", "Invalid extraction proposal", exception.getMessage());
    }
    @ExceptionHandler(ExtractionExceptions.ReviewConflictException.class)
    ResponseEntity<ProblemDetail> reviewConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "REVIEW_CONFLICT", "Review conflict", exception.getMessage());
    }
    @ExceptionHandler({
        ExtractionExceptions.ExtractionNotFoundException.class,
        ExtractionExceptions.ReviewNotFoundException.class
    })
    ResponseEntity<ProblemDetail> missing(RuntimeException exception) {
        String code = exception instanceof ExtractionExceptions.ExtractionNotFoundException
            ? "EXTRACTION_NOT_FOUND" : "REVIEW_NOT_FOUND";
        return problem(HttpStatus.NOT_FOUND, code, "Resource not found", exception.getMessage());
    }
    @ExceptionHandler(ExtractionExceptions.ModelUnavailableException.class)
    ResponseEntity<ProblemDetail> modelUnavailable(RuntimeException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE", "Model unavailable", exception.getMessage());
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "Data conflict", "The operation violates a database constraint");
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> missingAcquisition(NoSuchElementException exception) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", exception.getMessage());
    }
    @ExceptionHandler(DecisionExceptions.CandidateNotFoundException.class)
    ResponseEntity<ProblemDetail> candidateMissing(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND", "Candidate not found", exception.getMessage());
    }
    @ExceptionHandler(AgentRunController.PlannerUnavailableException.class)
    ResponseEntity<ProblemDetail> plannerUnavailable(AgentRunController.PlannerUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PLANNER_UNAVAILABLE",
            "Planner unavailable", exception.getMessage());
    }

    @ExceptionHandler(com.careeros.application.AgentSessionService.SessionNotFoundException.class)
    ResponseEntity<ProblemDetail> sessionNotFound(
        com.careeros.application.AgentSessionService.SessionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found", exception.getMessage());
    }

    @ExceptionHandler(CandidateProfileService.CandidateProfileNotFoundException.class)
    ResponseEntity<ProblemDetail> profileMissing(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND", "Candidate not found", exception.getMessage());
    }
    @ExceptionHandler(CareerPlanService.CandidateNotFoundException.class)
    ResponseEntity<ProblemDetail> planCandidateMissing(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND", "Candidate not found", exception.getMessage());
    }
    @ExceptionHandler(DecisionExceptions.JobNotFoundException.class)
    ResponseEntity<ProblemDetail> jobMissing(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found", exception.getMessage());
    }
    @ExceptionHandler(DecisionExceptions.JobNotAdmittedException.class)
    ResponseEntity<ProblemDetail> jobNotAdmitted(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "JOB_NOT_ADMITTED", "Job not admitted", exception.getMessage());
    }
    @ExceptionHandler(DecisionExceptions.DecisionNotFoundException.class)
    ResponseEntity<ProblemDetail> decisionMissing(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "DECISION_NOT_FOUND", "Decision not found", exception.getMessage());
    }
    @ExceptionHandler(CandidateDecisionDiffService.DecisionComparisonConflictException.class)
    ResponseEntity<ProblemDetail> decisionComparisonConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, "DECISION_COMPARISON_CONFLICT",
            "Decision comparison conflict", exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(
        HttpStatus status,
        String code,
        String title,
        String message
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message == null ? code : message);
        problem.setType(URI.create("https://career-os.local/problems/" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
