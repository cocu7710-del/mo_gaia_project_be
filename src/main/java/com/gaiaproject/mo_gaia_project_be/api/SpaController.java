package com.gaiaproject.mo_gaia_project_be.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** React SPA 라우팅 폴백 — FE 빌드(static/index.html)를 같은 오리진에서 서빙 (배포용).
 * /api·/ws·정적 파일 외의 FE 라우트는 전부 index.html로 넘겨 React Router가 처리한다. */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/rooms/new", "/rooms/{roomId}", "/games/{gameId}"})
    public String index() {
        return "forward:/index.html";
    }
}
