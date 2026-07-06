package com.guildworkman.api.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

public class JwtUtils {
    public static String generateAccessToken(Long id) {
        return JWT.create().withClaim("user_id", id).withIssuer("client_service")
                .sign(Algorithm.HMAC256("secret"));
    }
}
