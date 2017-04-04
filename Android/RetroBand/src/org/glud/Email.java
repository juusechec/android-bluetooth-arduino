package org.glud;

import java.io.UnsupportedEncodingException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.glud.Mail;

import android.widget.Toast;

public class Email {

	private static String dt = "", usuarioMail = "", mensajeMail = "";

	public static void main(String[] args) {
		// http://stackoverflow.com/questions/21252516/inside-eclipse-android-project-run-java-classes-with-mainstring-args-as-java
		 String mensaje = "Hola, me he caido.";
		 String subject = "ALERTA no-fallout";
		 String correo = "dibujatuvida-conpasion@yahoo.es";
		 sendMessage(mensaje, subject, correo);
	}

	public static boolean sendMessage(String mensaje, String subject, String correo) {

		boolean rpta = false;

		dt = "no.fallout@yandex.com";

		// https://yandex.com/support/mail-new/mail-clients.html
		Properties props = new Properties();
		// Nombre del host de correo, es smtp.gmail.com
		props.setProperty("mail.smtp.host", "smtp.yandex.com");
		// TLS si está disponible
		props.setProperty("mail.smtp.starttls.enable", "true");
		// Puerto de gmail para envio de correos
		props.setProperty("mail.smtp.port", "465");
		// Cuenta de correo en gmail
		props.setProperty("mail.smtp.user", dt);
		// Si requiere o no usuario y password para conectarse.
		props.setProperty("mail.smtp.auth", "true");
		// Para conexión con SSL
		props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

		Session session = Session.getDefaultInstance(props);
		// Verficiar el envio
		session.setDebug(true);
		MimeMessage message = new MimeMessage(session);
		try {
			message.setSubject(subject);
			message.setText(mensaje);
			Address address;
			try {
				address = new InternetAddress(dt, subject);
				message.setFrom(address);
			} catch (UnsupportedEncodingException ex) {
				System.out.println(Level.SEVERE);
				System.out.println(ex);
			}
			// La direccion de la persona a enviar
			Address address2 = new InternetAddress(correo, false);
			message.addRecipient(Message.RecipientType.TO, address2);
			Transport t = session.getTransport("smtp");
			t.connect(dt, "202G3Huo1M2jwn2ozFyAyl66Qk199UlJ");
			t.sendMessage(message, message.getAllRecipients());
			t.close();
			rpta = true;
		} catch (MessagingException ex) {
			return rpta;
		}
		return rpta;
	}

}
