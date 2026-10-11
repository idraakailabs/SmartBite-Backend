package com.idraak.smartbite.v2.error;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unknown /api/v2 paths get the v2 problem body instead of v1's generic error handler. */
@RestController
public class V2FallbackController {

    @RequestMapping("/api/v2/**")
    public void notFound() {
        throw new ApiException(ErrorCode.NOT_FOUND);
    }
}
