package com.careeros;

import static com.careeros.ExtractionApiModels.*;

import com.careeros.application.ExtractionPorts.ReviewDetails;
import com.careeros.application.ReviewService;
import com.careeros.domain.ReviewItem;
import com.careeros.domain.DomainEnums.ReviewStatus;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
final class ReviewController {
    private final ReviewService service;

    ReviewController(ReviewService service) { this.service = service; }

    @GetMapping
    PageResponse<ReviewItem> findPage(
        @RequestParam(name = "status", defaultValue = "PENDING") ReviewStatus status,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        var result = service.findPage(status, page, size);
        return new PageResponse<>(result.items(), result.page(), result.size(), result.totalElements());
    }

    @GetMapping("/{id}")
    ReviewDetails find(@PathVariable("id") UUID id) { return service.find(id); }

    @PostMapping("/{id}/actions")
    ReviewDetails act(
        @PathVariable("id") UUID id,
        @RequestBody ApplyReviewActionRequest request,
        Principal principal
    ) {
        // SecurityFilterChain 已要求该端点必须通过认证，principal 不应为空；
        // 真为空说明安全配置被改坏了，此时宁可失败也不能写一条无主的复核记录。
        Objects.requireNonNull(principal, "review actions require an authenticated principal");
        return service.act(request.toCommand(id, principal.getName()));
    }
}
