package com.astral.cms.config;

import com.astral.cms.common.CmsException;
import com.astral.cms.controller.AdminCmsCategoryController;
import com.astral.cms.controller.AdminCmsCommentController;
import com.astral.cms.controller.AdminCmsPageController;
import com.astral.cms.controller.AdminCmsPostController;
import com.astral.cms.controller.AdminCmsSettingController;
import com.astral.cms.controller.AdminCmsShareController;
import com.astral.cms.controller.AdminCmsTagController;
import com.astral.cms.controller.AppCmsController;
import com.astral.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 内容管理插件统一异常处理
 * <p>作用于 com.astral.cms.controller 下全部控制器，返回宿主 Result 结构
 * （code=200 成功；业务异常 code=400/500，HTTP 状态保持 200 由前端按 code 判定）。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(assignableTypes = {
        AppCmsController.class,
        AdminCmsPostController.class,
        AdminCmsCategoryController.class,
        AdminCmsTagController.class,
        AdminCmsPageController.class,
        AdminCmsCommentController.class,
        AdminCmsSettingController.class,
        AdminCmsShareController.class
})
public class CmsGlobalExceptionHandler {

    @ExceptionHandler(CmsException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleCmsException(CmsException e) {
        log.warn("[CmsPlugin] 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.error(400, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.error(400, msg);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("[CmsPlugin] 系统异常", e);
        return Result.error(500, "系统出现错误");
    }
}
