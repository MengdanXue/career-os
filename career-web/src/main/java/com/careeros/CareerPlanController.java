package com.careeros;

import com.careeros.application.planning.CareerPlan;
import com.careeros.application.planning.CareerPlanService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/career-plan")
class CareerPlanController {
    private final CareerPlanService plans;
    private final Clock clock;

    CareerPlanController(CareerPlanService plans, Clock clock) {
        this.plans = plans; this.clock = clock;
    }

    @GetMapping
    CareerPlan careerPlan(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam(name = "targetYear", required = false) Integer targetYear,
        @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveAsOf = asOf == null ? today : asOf;
        if (effectiveAsOf.isAfter(today)) throw new IllegalArgumentException("asOf 不能晚于服务器当前日期 " + today);
        int effectiveTargetYear = targetYear == null ? effectiveAsOf.getYear() + 1 : targetYear;
        int minimum = today.getYear();
        int maximum = minimum + 5;
        if (effectiveTargetYear < minimum || effectiveTargetYear > maximum) {
            throw new IllegalArgumentException("targetYear 必须在 " + minimum + " 至 " + maximum + " 之间");
        }
        return plans.generate(candidateId, effectiveTargetYear, effectiveAsOf);
    }
}
