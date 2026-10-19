package com.whatsappmvp.application.service;

import com.whatsappmvp.infrastructure.persistence.entity.FaqItemEntity;
import com.whatsappmvp.infrastructure.persistence.jpa.FaqJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Scoring del motor de FAQs: el plural/singular cuenta como palabra completa,
 * pero el singular derivado NO puede entrar por substring dentro de otra palabra
 * ("dia" dentro de "mediodía" hacía que la FAQ de horarios se pegara a cualquier mensaje).
 */
class FaqMatchingServiceTest {

    private static final String DOMICILIO = "No. Actualmente no contamos con servicio de domicilio.";
    private static final String HORARIO = "Nuestro horario de atención es: sábados y domingos.";
    private static final String MASCOTA = "¡Sí! Somos un restaurante pet friendly.";

    private final FaqJpaRepository repository = org.mockito.Mockito.mock(FaqJpaRepository.class);
    private final FaqMatchingService service = new FaqMatchingService(repository);

    private static FaqItemEntity faq(String question, String answer, String... keywords) {
        return FaqItemEntity.builder().question(question).answer(answer).keywords(keywords).build();
    }

    @BeforeEach
    void givenTheFaqCatalog() {
        when(repository.findByIsActiveTrueOrderByPriorityDesc()).thenReturn(List.of(
                faq("¿Tienen servicio de domicilio?", DOMICILIO, "domicilio", "delivery", "envio"),
                faq("¿Cuál es el horario de atención?", HORARIO, "horario", "hora", "abren", "dias"),
                faq("¿Puedo llevar mi mascota?", MASCOTA, "perro", "mascota", "pet friendly"),
                faq("¿Tienen WiFi?", "No contamos con servicio de WiFi.", "wifi", "red", "internet")));
    }

    @Test
    void pluralOfTheKeywordMatchesAsAWholeWord() {
        assertThat(service.findMatch("¿hacen domicilios?")).contains(DOMICILIO);
    }

    @Test
    void derivedSingularDoesNotSneakIntoAnotherWord() {
        // "dia" (3 letras, singular de "dias") no debe matchear dentro de "mediodía"
        assertThat(service.findMatch("reserva para 4 mediodía")).isEmpty();
        assertThat(service.findMatch("¿a qué hora abren?")).contains(HORARIO);
    }

    @Test
    void shortKeywordStillMatchesByContainment() {
        assertThat(service.findMatch("¿hay internet o red acá?"))
                .contains("No contamos con servicio de WiFi.");
    }

    @Test
    void sideFaqOnlyWhenTheMatchIsStrong() {
        assertThat(service.findMatch("reserva para 10 y voy con mi perro", f -> false, 3)).contains(MASCOTA);
        assertThat(service.findMatch("reserva para 4 mediodía", f -> false, 3)).isEmpty();
    }

    @Test
    void reservationFaqCanBeExcludedFromTheSideAnswer() {
        assertThat(service.findMatch("reserva para 4 y voy con mi perro",
                f -> f.getQuestion().toLowerCase().contains("mascota"), 3)).isEmpty();
        assertThat(service.findMatch("reserva para 4 y voy con mi perro", f -> false, 3)).contains(MASCOTA);
    }
}
