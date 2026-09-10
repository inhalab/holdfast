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

    /**
     * 이 요청을 처리한 인스턴스. <b>결정 규칙은 {@code InstanceIdentityInitializer} 하나에 있다</b>
     * — 로컬은 {@code docker-compose}가 준 {@code app1}/{@code app2}, Fargate는 태스크
     * 메타데이터에서 읽은 값이다(이슈 #151).
     */
    @Value("${holdfast.instance-id:local}")
    private String instanceId;

    /**
     * 로컬에서만 켜지는 화면(이슈 #124, 판정은 #138). 바닥 내비게이션이 이 값으로
     * 링크를 감춘다.
     *
     * <p><b>같은 프로퍼티를 컨트롤러와 화면이 나눠 쓴다.</b> {@code DemoRaceController}와
     * 관리자 컨트롤러 셋은 {@code @ConditionalOnProperty}로 빈 자체가 만들어지지 않고,
     * 화면은 여기서 받은 값으로 링크를 지운다. <b>각자 읽게 두면 한쪽만 바뀌어
     * "링크는 있는데 404"가 된다</b> — 그래서 화면이 읽는 자리를 이 클래스 하나로
     * 모으고, 둘이 함께 움직이는지는 {@code LocalOnlyScreenTest}가 지킨다.
     */
    @Value("${holdfast.demo.enabled:true}")
    private boolean demoEnabled;

    @Value("${holdfast.admin.enabled:true}")
    private boolean adminEnabled;

    /** 이 요청을 처리한 앱 인스턴스. 모든 화면의 바닥에 찍힌다. */
    @ModelAttribute("instanceId")
    public String instanceId() {
        return instanceId;
    }

    @ModelAttribute("demoEnabled")
    public boolean demoEnabled() {
        return demoEnabled;
    }

    @ModelAttribute("adminEnabled")
    public boolean adminEnabled() {
        return adminEnabled;
    }
}
