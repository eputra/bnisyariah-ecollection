package id.ac.tazkia.payment.bnisyariah.ecollection.service;

import com.bni.encrypt.BNIHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.ac.tazkia.payment.bnisyariah.ecollection.constants.BniEcollectionConstants;
import id.ac.tazkia.payment.bnisyariah.ecollection.dao.VirtualAccountDao;
import id.ac.tazkia.payment.bnisyariah.ecollection.dao.VirtualAccountRequestDao;
import id.ac.tazkia.payment.bnisyariah.ecollection.dto.CreateVaRequest;
import id.ac.tazkia.payment.bnisyariah.ecollection.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

@Service @Transactional
public class BniEcollectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BniEcollectionService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String TIMEZONE = "GMT+07:00";

    @Value("${bni.client-id}") private String clientId;
    @Value("${bni.client-key}") private String clientKey;
    @Value("${bni.server-url}") private String serverUrl;

    @Autowired private RunningNumberService runningNumberService;
    @Autowired private VirtualAccountDao virtualAccountDao;
    @Autowired private VirtualAccountRequestDao virtualAccountRequestDao;
    @Autowired private ObjectMapper objectMapper;

    @Async
    public void createVirtualAccount(VirtualAccountRequest request){
        String datePrefix = DATE_FORMAT.format(LocalDateTime.now(ZoneId.of(TIMEZONE)));
        String prefix = datePrefix;
        Long runningNumber = runningNumberService.getNumber(prefix);
        String trxId = datePrefix + String.format("%06d", runningNumber);

        CreateVaRequest createVaRequest = CreateVaRequest.builder()
                .clientId(clientId)
                .customerEmail(request.getEmail())
                .customerName(request.getName())
                .customerPhone(request.getPhone())
                .datetimeExpired(toIso8601(request.getExpireDate()))
                .description(request.getDescription())
                .trxAmount(request.getAmount().setScale(0, BigDecimal.ROUND_HALF_EVEN).toString())
                .trxId(trxId)
                .virtualAccount("8"+clientId + request.getNumber())
                .build();

        if(AccountType.CLOSED.equals(request.getAccountType())){
            createVaRequest.setBillingType(BniEcollectionConstants.BILLING_TYPE_CLOSED);
        } else if(AccountType.INSTALLMENT.equals(request.getAccountType())){
            createVaRequest.setBillingType(BniEcollectionConstants.BILLING_TYPE_INSTALLMENT);
        } else if(AccountType.OPEN.equals(request.getAccountType())){
            createVaRequest.setBillingType(BniEcollectionConstants.BILLING_TYPE_OPEN);
        }

        try {
            String requestJson = objectMapper.writeValueAsString(createVaRequest);
            LOGGER.debug("Create VA Request : {}", requestJson);
            Map<String, String> hasil = executeRequest(createVaRequest);
            LOGGER.debug("Create VA Response : {}", objectMapper.writeValueAsString(hasil));
            if(hasil != null) {
                String responseStatus = hasil.get("status");
                if (BniEcollectionConstants.BNI_RESPONSE_STATUS_SUCCESS.equals(responseStatus)) {
                    VirtualAccount va = new VirtualAccount();
                    BeanUtils.copyProperties(request, va);
                    va.setAccountStatus(AccountStatus.ACTIVE);
                    va.setCreateTime(LocalDateTime.now());
                    virtualAccountDao.save(va);
                    LOGGER.info("BNI : VA [{}-{}] sukses diupdate", va.getNumber(), va.getName());
                    request.setRequestStatus(RequestStatus.SUCCESS);
                    virtualAccountRequestDao.save(request);
                } else {
                    LOGGER.error("BNI : Error update VA [{}-{}], response status {}", request.getNumber(), request.getName(), responseStatus);
                    request.setRequestStatus(RequestStatus.ERROR);
                    virtualAccountRequestDao.save(request);
                }
            } else {
                LOGGER.error("BNI : Error update VA [{}-{}], response null", request.getNumber(), request.getName());
                request.setRequestStatus(RequestStatus.ERROR);
                virtualAccountRequestDao.save(request);
            }
        } catch (Exception err){
            LOGGER.warn(err.getMessage(), err);
            request.setRequestStatus(RequestStatus.ERROR);
            virtualAccountRequestDao.save(request);
        }
    }

    private String toIso8601(LocalDate d) {
        return d.atStartOfDay(ZoneId.of(TIMEZONE))
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private Map<String, String> executeRequest(Object request) throws Exception {
        String responseData = sendRequest(request);

        if(responseData != null) {
            LOGGER.debug("Response Data : {}", responseData);

            String decryptedResponse = BNIHash.parseData(responseData, clientId, clientKey);
            LOGGER.debug("Decrypted Response : {}", decryptedResponse);

            return objectMapper.readValue(decryptedResponse, Map.class);
        }
        return null;
    }

    private String sendRequest(Object requestData) throws Exception {
        String rawData = objectMapper.writeValueAsString(requestData);
        LOGGER.debug("BNI : Raw Data : {}",rawData);

        String encryptedData = BNIHash.hashData(rawData, clientId, clientKey);
        LOGGER.debug("BNI : Encrypted Data : {}",encryptedData);

        Map<String, String> wireData = new TreeMap<>();
        wireData.put("client_id", clientId);
        wireData.put("data", encryptedData);

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<Map<String, String>>(wireData, headers);
        ResponseEntity<Map> response = restTemplate.exchange(serverUrl, HttpMethod.POST, request, Map.class);
        Map<String, String> hasil = response.getBody();
        String responseStatus = hasil.get("status");
        LOGGER.debug("BNI : Response Status : {}",responseStatus);

        if(!"000".equals(responseStatus)) {
            LOGGER.error("BNI : Response status : {}", responseStatus);
        }

        return hasil.get("data");
    }
}
