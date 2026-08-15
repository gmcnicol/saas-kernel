package io.github.gmcnicol.crm;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class WebSecurityConfiguration {

    @Bean
    SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/presentation/intents/**"))
                .build();
    }
}
