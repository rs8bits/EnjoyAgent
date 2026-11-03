package com.enjoy.sandbox;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ManagerAuthentication extends OncePerRequestFilter {
  private final byte[] token;

  public ManagerAuthentication(ManagerProperties properties) throws IOException {
    String secret = Files.readString(properties.tokenFile()).strip();
    if (secret.length() < 32)
      throw new IllegalArgumentException(
          "Sandbox manager token must contain at least 32 characters");
    token = secret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().equals("/health") && request.getMethod().equals("GET")) {
      chain.doFilter(request, response);
      return;
    }
    String provided = request.getHeader("X-Sandbox-Token");
    if (provided == null
        || !MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8))) {
      response.sendError(401);
      return;
    }
    chain.doFilter(request, response);
  }
}
