package com.nailinai.ragent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.nailinai.ragent",
        "com.nailinai.ragent.framework",
        "com.nailinai.ragent.infra"
})
@MapperScan("com.nailinai.ragent.mapper")
public class RagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }
}