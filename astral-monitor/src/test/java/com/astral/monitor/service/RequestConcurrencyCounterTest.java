package com.astral.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RequestConcurrencyCounterTest {
    @Test
    void sseRedispatchReleasesTheSingleInFlightRequest() throws Exception {
        var counter = new RequestConcurrencyCounter();
        var controller = new StreamFixture();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(counter)
                .build();
        var result =
                mvc.perform(get("/stream")).andExpect(request().asyncStarted()).andReturn();
        assertEquals(1, counter.getActiveRequests());
        controller.emitter.send("fixture");
        controller.emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertEquals(0, counter.getActiveRequests());
    }

    @RestController
    static class StreamFixture {
        SseEmitter emitter;

        @GetMapping("/stream")
        SseEmitter stream() {
            emitter = new SseEmitter();
            return emitter;
        }
    }
}
