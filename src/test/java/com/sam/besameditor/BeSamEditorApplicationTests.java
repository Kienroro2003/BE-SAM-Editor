package com.sam.besameditor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest
class BeSamEditorApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void main_ShouldStartApplication() {
        String[] args = {
                "--spring.main.web-application-type=none",
                "--spring.main.lazy-initialization=true",
                "--spring.main.banner-mode=off"
        };

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> BeSamEditorApplication.main(args));
    }

}
