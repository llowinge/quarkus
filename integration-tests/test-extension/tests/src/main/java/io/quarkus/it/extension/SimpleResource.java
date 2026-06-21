package io.quarkus.it.extension;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.microprofile.config.ConfigProvider;

@WebServlet(name = "SimpleResource", urlPatterns = "/simple/message")
public class SimpleResource extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String message = ConfigProvider.getConfig()
                .getOptionalValue("test.message", String.class)
                .orElse("unset");
        resp.getWriter().write(message);
    }
}
