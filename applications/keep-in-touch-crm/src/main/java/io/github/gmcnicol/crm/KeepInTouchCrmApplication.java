package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KeepInTouchCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeepInTouchCrmApplication.class, args);
    }

    @Bean
    SemanticPack crmSemanticPack() {
        return () -> "keep-in-touch-crm";
    }

    @Bean
    PresentationPack crmPresentationPack() {
        return () -> "keep-in-touch-crm-default";
    }
}
