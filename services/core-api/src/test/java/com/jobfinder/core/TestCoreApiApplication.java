package com.jobfinder.core;

import org.springframework.boot.SpringApplication;

public class TestCoreApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(CoreApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
