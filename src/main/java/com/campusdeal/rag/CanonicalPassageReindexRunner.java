package com.campusdeal.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.List;

/** Opt-in command runner: --campusdeal.rag.reindex=true. */
@Slf4j
@Component
public class CanonicalPassageReindexRunner implements ApplicationRunner {

    @Value("${campusdeal.rag.reindex:false}")
    private boolean enabled;
    @Resource private CanonicalPassageReindexService service;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        List<String> dryRunValues = args.getOptionValues("rag-reindex-dry-run");
        boolean dryRun = args.containsOption("rag-reindex-dry-run")
                && (dryRunValues == null || !dryRunValues.contains("false"));
        boolean resume = args.containsOption("rag-reindex-resume");
        CanonicalPassageReindexService.ReindexReport report = service.reindex(dryRun, resume);
        log.info("Canonical passage reindex: documents={}, passages={}, embedded={}, dryRun={}, nextOffset={}",
                report.documentCount(), report.passageCount(), report.embeddedCount(), report.dryRun(), report.nextOffset());
    }
}
