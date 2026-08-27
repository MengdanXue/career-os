package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionHttpPorts.DiscoveredLink;
import com.careeros.application.AcquisitionHttpPorts.SourceDiscoverer;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.net.URI;
import java.util.List;
import java.util.Objects;

public final class RoutingSourceDiscoverer implements SourceDiscoverer {
    private final StaticHtmlSourceDiscoverer staticHtml;
    private final HospitalOfficialEvidenceDiscoverer hospital;

    public RoutingSourceDiscoverer(
        StaticHtmlSourceDiscoverer staticHtml,
        HospitalOfficialEvidenceDiscoverer hospital
    ) {
        this.staticHtml = Objects.requireNonNull(staticHtml, "staticHtml");
        this.hospital = Objects.requireNonNull(hospital, "hospital");
    }

    @Override
    public List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html) {
        return delegate(source).discover(source, pageUri, html);
    }

    @Override
    public List<DiscoveredLink> discoverAll(RecruitmentSource source, URI pageUri, byte[] html) {
        return delegate(source).discoverAll(source, pageUri, html);
    }

    public List<DiscoveredLink> discover(
        RecruitmentSource source, ListingEntryContract entry, URI pageUri, byte[] html
    ) {
        SourceDiscoverer selected = delegate(entry);
        if (selected == staticHtml) return staticHtml.discover(source, entry, pageUri, html);
        return selected.discover(scopedSource(source, entry), pageUri, html);
    }

    public List<DiscoveredLink> discoverAll(
        RecruitmentSource source, ListingEntryContract entry, URI pageUri, byte[] html
    ) {
        SourceDiscoverer selected = delegate(entry);
        if (selected == staticHtml) return staticHtml.discoverAll(source, entry, pageUri, html);
        return selected.discoverAll(scopedSource(source, entry), pageUri, html);
    }

    private SourceDiscoverer delegate(RecruitmentSource source) {
        Object configured = source.configuration().get("adapterType");
        String adapter = configured == null ? null : configured.toString();
        if ("JCMS_LISTING".equals(adapter) || "STATIC_HTML".equals(adapter)) return staticHtml;
        if ("HOSPITAL_OFFICIAL_EVIDENCE".equals(adapter)) return hospital;
        throw new IllegalArgumentException("Unsupported adapterType: " + adapter);
    }

    private SourceDiscoverer delegate(ListingEntryContract entry) {
        Object configured = entry.configuration().get("adapterType");
        String adapter = configured == null ? "STATIC_HTML" : configured.toString();
        if ("JCMS_LISTING".equals(adapter) || "STATIC_HTML".equals(adapter)) return staticHtml;
        if ("HOSPITAL_OFFICIAL_EVIDENCE".equals(adapter)) return hospital;
        throw new IllegalArgumentException("Unsupported adapterType: " + adapter);
    }

    private static RecruitmentSource scopedSource(
        RecruitmentSource source, ListingEntryContract entry
    ) {
        return new RecruitmentSource(
            source.id(), source.code(), source.name(), source.baseUri(), entry.entryUri(),
            source.sourceType(), source.region(), source.crawlMode(), source.enabled(),
            source.cronExpression(), source.timeZone(), source.minimumRequestInterval(),
            entry.configuration(), source.lastSuccessAt(), source.lastFailureAt(), source.nextDueAt(),
            source.consecutiveFailureCount(), source.createdAt(), source.updatedAt());
    }
}
