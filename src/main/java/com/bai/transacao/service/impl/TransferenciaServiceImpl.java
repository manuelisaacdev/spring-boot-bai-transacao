package com.bai.transacao.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;

import com.bai.transacao.exception.BadRequestException;
import com.bai.transacao.kafka.KafkaProducerService;
import com.bai.transacao.kafka.TransferenciaPayload;
import com.bai.transacao.model.Status;
import com.bai.transacao.model.TipoTransferencia;
import com.bai.transacao.model.Transferencia;
import com.bai.transacao.repository.TransferenciaRepository;
import com.bai.transacao.service.TransferenciaService;
import com.bai.transacao.util.PasswordGeneratorManager;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class TransferenciaServiceImpl extends AbstractServiceImpl<Transferencia, UUID, TransferenciaRepository> implements TransferenciaService  {
	private final String bankCode;
	private final String prefixIBan;
	private final Integer sizeOrderNumber;
	private final KafkaProducerService kafkaProducerService;
	private final PasswordGeneratorManager passwordGeneratorManager;

	public TransferenciaServiceImpl(TransferenciaRepository repository, HttpServletRequest request, MessageSource messageSource,
			@Value("${application.bai.code}") String bankCode, @Value("${application.bai.prefix-iban}") String prefixIBan, 
			@Value("${application.bai.size-account-number}") Integer sizeOrderNumber, 
			KafkaProducerService kafkaProducerService, PasswordGeneratorManager passwordGeneratorManager) {
		super(repository, request, messageSource);
		this.bankCode = bankCode;
		this.prefixIBan = prefixIBan;
		this.sizeOrderNumber = sizeOrderNumber;
		this.kafkaProducerService = kafkaProducerService;
		this.passwordGeneratorManager = passwordGeneratorManager;
	}
	
	@Override
	public List<Transferencia> findAllByIBAN(String iban, String orderBy, Direction direction) throws BadRequestException {
		this.validateIBAN(iban);
		return super.repository.findAllByIbanContaOrigemOrIbanContaDestino(iban, iban);
	}
	
	@Override
	public Page<Transferencia> paginationByIBAN(int page, int size, String iban, String orderBy, Direction direction) throws BadRequestException {
		this.validateIBAN(iban);
		return super.repository.findAllByIbanContaOrigemOrIbanContaDestino(iban, iban, PageRequest.of(page, size, direction, orderBy));
	}

	@Override
	public Transferencia create(Transferencia transferencia) {
		validateIBAN(transferencia.getIbanContaOrigem());
		transferencia.setStatus(Status.PROCESSO);
		transferencia.setTipoTransferencia(transferencia.getIbanContaDestino().startsWith(prefixIBan) ? TipoTransferencia.INTERNA : TipoTransferencia.INTERBANCARIA);
		transferencia.setNumeroOrdem(bankCode + passwordGeneratorManager.generateOnlyDigits(sizeOrderNumber, 1));
		Transferencia entity = super.save(transferencia);
		kafkaProducerService.send(TransferenciaPayload.builder()
		.montante(transferencia.getMontante())
		.numeroOrdem(transferencia.getNumeroOrdem())
		.ibanContaOrigem(transferencia.getIbanContaOrigem())
		.ibanContaDestino(transferencia.getIbanContaDestino())
		.tipoTransferencia(transferencia.getTipoTransferencia())
		.build());
		return entity;
	}

	@Override
	public void updateStatus(String numeroOrdem, Status status) {
		Optional<Transferencia> transferencia = super.repository.findByNumeroOrdem(numeroOrdem);
		if (transferencia.isPresent()) {
			transferencia.get().setStatus(status);
			super.save(transferencia.get());
		}
	}
	
	private void validateIBAN(String iban) throws BadRequestException {
		if (!iban.startsWith(prefixIBan)) {
			throw new BadRequestException(super.messageSource.getMessage("iban.invalido", new String[]{iban}, super.request.getLocale()));
		}
	}
}
