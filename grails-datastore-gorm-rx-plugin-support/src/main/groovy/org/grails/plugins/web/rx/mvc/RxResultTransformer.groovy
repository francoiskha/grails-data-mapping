package org.grails.plugins.web.rx.mvc

import grails.artefact.Controller
import grails.artefact.controller.RestResponder
import grails.async.web.AsyncGrailsWebRequest
import grails.rest.render.ContainerRenderer
import grails.rest.render.RenderContext
import grails.rest.render.Renderer
import grails.rest.render.RendererRegistry
import grails.util.GrailsWebUtil
import grails.web.mime.MimeType
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.plugins.web.async.GrailsAsyncContext
import org.grails.plugins.web.rest.render.ServletRenderContext
import org.grails.web.errors.GrailsExceptionResolver
import org.grails.web.servlet.mvc.ActionResultTransformer
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.util.GrailsApplicationAttributes
import org.grails.web.util.WebUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.async.AsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncManager
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.servlet.ModelAndView
import rx.Observable
import rx.Subscriber

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Transforms an RX result into async dispatch
 *
 * @author Graeme Rocher
 * @since 6.0
 */
@CompileStatic
@Slf4j
class RxResultTransformer implements ActionResultTransformer, Controller, RestResponder {
    final MimeType[] mimeTypes = [ MimeType.JSON, MimeType.HAL_JSON, MimeType.XML, MimeType.HAL_XML ] as MimeType[]
    final Class<Observable> targetType = Observable
    final Class componentType = Object
    String encoding = GrailsWebUtil.DEFAULT_ENCODING

    @Autowired(required = false)
    GrailsExceptionResolver exceptionResolver

    @Autowired(required = false)
    @CompileDynamic
    void setRendererRegistry(RendererRegistry registry) {
        registry.addContainerRenderer(Object, this as ContainerRenderer<Observable, Object>)
    }

    List<String> getResponseFormats() {
        (List<String>)GrailsWebRequest.lookup().getControllerClass().getPropertyValue("responseFormats")
    }

    Object transformActionResult(GrailsWebRequest webRequest, String viewName, Object actionResult, boolean isRender = false) {
        if(actionResult instanceof Observable) {
            Observable observable = (Observable)actionResult

            final request = webRequest.getCurrentRequest()
            WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request)
            final response = webRequest.getResponse()

            AsyncWebRequest asyncWebRequest = new AsyncGrailsWebRequest(request, response, webRequest.servletContext)
            asyncManager.setAsyncWebRequest(asyncWebRequest)

            asyncWebRequest.startAsync()
            request.setAttribute(GrailsApplicationAttributes.ASYNC_STARTED, true)
            def asyncContext = asyncWebRequest.asyncContext
            asyncContext = new GrailsAsyncContext(asyncContext, webRequest)
            asyncContext.start {

                List rxResults = []

                observable
                    .switchIfEmpty(Observable.create({ Subscriber s ->
                            s.onNext(null)
                            s.onCompleted()
                    } as Observable.OnSubscribe))
                        .subscribe(new Subscriber() {
                    @Override
                    void onCompleted() {
                        GrailsWebRequest rxWebRequest = new GrailsWebRequest((HttpServletRequest)asyncContext.request, (HttpServletResponse)asyncContext.response, request.getServletContext())
                        WebUtils.storeGrailsWebRequest(rxWebRequest)

                        try {
                            if(rxResults.isEmpty()) {
                                respond((Object)null)
                            }
                            else {
                                if(rxResults.size() == 1) {
                                    def first = rxResults.get(0)
                                    respond first
                                }
                                else {
                                    respond rxResults
                                }
                            }
                            def modelAndView = asyncContext.getRequest().getAttribute(GrailsApplicationAttributes.MODEL_AND_VIEW)
                            if(modelAndView != null && !isRender) {
                                asyncContext.dispatch()
                            }
                            else {
                                if(isRender) {
                                    asyncContext.response.flushBuffer()
                                }
                                asyncContext.complete()
                            }
                        } finally {
                            rxWebRequest.requestCompleted()
                            WebUtils.clearGrailsWebRequest()
                        }
                    }

                    @Override
                    void onError(Throwable e) {
                        if(!asyncContext.response.isCommitted()) {
                            def httpServletResponse = (HttpServletResponse) asyncContext.response
                            if(exceptionResolver != null) {

                                def modelAndView = exceptionResolver.resolveException((HttpServletRequest) asyncContext.request, httpServletResponse, this, (Exception) e)
                                asyncContext.getRequest().setAttribute(GrailsApplicationAttributes.MODEL_AND_VIEW, modelAndView);
                                asyncContext.dispatch()

                            }
                            else {
                                log.error("Async Dispatch Error: ${e.message}", e)
                                httpServletResponse.sendError(500, "Async Dispatch Error: ${e.message}")
                                asyncContext.complete()
                            }
                        }
                    }

                    @Override
                    void onNext(Object o) {
                        rxResults.add(o)
                    }
                })
            }
            webRequest.setRenderView(false)
            return null
        }
        return actionResult
    }

    void render(Observable object, RenderContext context) {
        final mimeType = context.acceptMimeType ?: MimeType.JSON
        context.setContentType( GrailsWebUtil.getContentType(mimeType.name, encoding) )

        ServletRenderContext servletRenderContext = (ServletRenderContext) context
        transformActionResult(servletRenderContext.webRequest, context.viewName, object, true)
    }

}
