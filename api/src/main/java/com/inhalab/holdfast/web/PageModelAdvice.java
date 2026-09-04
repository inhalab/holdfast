package com.inhalab.holdfast.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 서버렌더 화면이 공통으로 쓰는 모델 값.
 *
 * <h2>응답한 인스턴스를 화면에 찍는다</h2>
 *
 * <p>이 시스템은 <b>앱 2대를 nginx 뒤에 둔다</b>(design-spec 5.5, concurrency-spec
 * 0.4). 그런데 그 사실이 화면에는 아무 데도 나타나지 않았다 — {@code /status} API를
 * 직접 호출해야만 보였다. 구성도에만 있고 화면에 없는 구조는 <b>발표에서 "그렇게
 * 되어 있다"고 말할 수는 있어도 보여 줄 수가 없다.</b>
 *
 * <p>새로고침할 때마다 {@code app1}/{@code app2}가 번갈아 찍히는 것이 로드밸런싱의
 * 가장 짧은 증거다. 꾸미기가 아니라 <b>이미 참인 문장을 보이게 하는 것</b>이다.
 *
 * <p>같은 값이 이미 두 곳에 있다는 점도 이 배지의 쓸모다 — 플래시 메시지가 왜
 * 유실되는지(5.5), 폴링이 왜 두 인스턴스에 흩어지는지가 이 값 하나로 설명된다.
 *
 * <h2>범위를 {@code web} 패키지로 한정한다</h2>
 *
 * <p>{@code basePackages}를 지정하지 않으면 이 advice가 {@code reservation/}·
 * {@code seat/}의 REST 컨트롤러에까지 붙는다. 모델 값은 JSON 응답에서 무시되므로
 * 동작이 깨지지는 않지만, <b>남의 패키지에 조용히 걸리는 전역 설정을 두지
 * 않는다</b>(#79 이후 지켜 온 경계). 이 배지가 필요한 것은 서버렌더 화면뿐이다.
 */
@ControllerAdvice(basePackages = "com.inhalab.holdfast.web")
public class PageModelAdvice {

    /** {@code INSTANCE_ID} 환경변수. docker-compose가 app1/app2로 준다. */
    @Value("${holdfast.instance-id:unknown}")
    private String instanceId;

    /** 이 요청을 처리한 앱 인스턴스. 모든 화면의 바닥에 찍힌다. */
    @ModelAttribute("instanceId")
    public String instanceId() {
        return instanceId;
    }
}
