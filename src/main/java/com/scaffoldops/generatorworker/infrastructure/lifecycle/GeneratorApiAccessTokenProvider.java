package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import java.util.Optional;

interface GeneratorApiAccessTokenProvider {

    Optional<String> accessToken();

    void invalidate();
}
