package dev.sumin.skeleton.app.workbench

import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc   // MockMvc 가 없어도 되는 시험이지만, 있고 없고가 컨텍스트 캐시 키라 기본 컨텍스트를 같이 쓰려고 맞춘다
class WorkbenchApplicationTests {

	@Test
	fun contextLoads() {
	}

}
