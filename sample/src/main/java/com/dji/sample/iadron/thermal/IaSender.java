package com.dji.sample.iadron.thermal;

/**
 * Cómo llega el mensaje "A|<base64>" a la aplicación de IA.
 */
public interface IaSender {

    void send(String message) throws Exception;
}