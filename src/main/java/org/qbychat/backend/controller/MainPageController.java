package org.qbychat.backend.controller;

import io.swagger.annotations.ApiOperation;
import org.qbychat.backend.entity.RestBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController("/")
public class MainPageController {
    @GetMapping("/")
    @ResponseBody
    @ApiOperation("MainPage")
    public RestBean<String> index() {
        return RestBean.success(String.format("QMessengerBackend %d", System.currentTimeMillis()));
    }
}
