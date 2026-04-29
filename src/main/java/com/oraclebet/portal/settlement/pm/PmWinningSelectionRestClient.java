package com.oraclebet.portal.settlement.pm;

import com.oraclebet.contracts.pm.PmWinningSelectionClient;
import com.oraclebet.contracts.pm.WinningSelectionDto;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class PmWinningSelectionRestClient implements PmWinningSelectionClient {

    private final SettlementPmValidationProperties props;

    public PmWinningSelectionRestClient(SettlementPmValidationProperties props) {
        this.props = props;
    }

    @Override
    public WinningSelectionDto getWinningSelection(String eventId, String marketId) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        RestTemplate restTemplate = new RestTemplate(factory);

        String url = UriComponentsBuilder
                .fromUriString(normalizeBaseUrl(props.getBaseUrl()) + "/api/pm/winning-selection")
                .queryParam("eventId", eventId)
                .queryParam("marketId", marketId)
                .toUriString();
        return restTemplate.getForObject(url, WinningSelectionDto.class);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("settlement.pm-validation.base-url is blank");
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
