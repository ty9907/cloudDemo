package com.example.consumer.hystrix;


import com.netflix.hystrix.HystrixCommand;
import org.springframework.web.client.RestTemplate;

public class MyHystrixCommand extends HystrixCommand<String> {

    private RestTemplate restTemplate;

    public MyHystrixCommand(Setter setter,RestTemplate restTemplate) {
        super(setter);
        this.restTemplate=restTemplate;
    }

    @Override
    protected String run() throws Exception {
        return restTemplate.getForObject("http://provider-a/hello", String.class);
    }


    /**
     * 远程服务异常，超市调用此方法
     * @return
     */
    @Override
    protected String getFallback() {
        return super.getFallback();
    }
}
