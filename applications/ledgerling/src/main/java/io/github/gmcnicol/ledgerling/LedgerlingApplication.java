package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LedgerlingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerlingApplication.class, args);
    }

    @Bean
    SemanticPack ledgerlingSemanticPack() {
        return () -> "ledgerling";
    }

    @Bean
    PresentationPack ledgerlingPresentationPack() {
        return () -> "ledgerling-default";
    }
}
