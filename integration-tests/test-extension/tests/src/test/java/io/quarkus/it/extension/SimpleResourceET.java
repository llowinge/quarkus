package io.quarkus.it.extension;

import static org.hamcrest.CoreMatchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
public class SimpleResourceET {

    @Test
    public void testMessageFirst() {
        RestAssured.get("/simple/message")
                .then()
                .statusCode(200)
                .body(is("first"));
    }

    @Test
    public void testMessageSecond() {
        RestAssured.get("/simple/message")
                .then()
                .statusCode(200)
                .body(is("second"));
    }
}
