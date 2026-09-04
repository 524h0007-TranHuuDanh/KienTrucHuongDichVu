package com.tdtu.ibanking.payment.exception;

public class ServiceBusyException extends RuntimeException {
    public ServiceBusyException(String message) { super(message); }
}