package ar.com.samsistemas.middleware.notificadortramite.routebuilder;

import javax.annotation.Resource;
import javax.ejb.Startup;
import javax.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;

import ar.com.samsistemas.middleware.notificadortramite.entities.TramiteMailSocioDTO;
import com.google.gson.Gson;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.cdi.ContextName;
import org.apache.camel.impl.CompositeRegistry;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.SimpleRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Startup
@ApplicationScoped
@ContextName("NotificadorTramite-context")
public class NotificadorTramiteRouteBuilder extends RouteBuilder {

	@Resource(lookup = "java:jboss/jdbc/mysql")
	private DataSource dataSource;

	private static final String MAIL_ENDPOINT_URL = "NotificadorTramite.ProviderURI";
	private static final String MAIL_ENDPOINT_TOKEN = "NotificadorTramite.ProviderToken"; //TODO: falta agregar esto en JBoss

	@Override
	public void configure() {

		/** Bind JDBC datasource to camel context **/

		SimpleRegistry reg = new SimpleRegistry();
		reg.put("mysqlDS", dataSource);

		CompositeRegistry compositeRegistry = new CompositeRegistry();
		compositeRegistry.addRegistry(getContext().getRegistry());
		compositeRegistry.addRegistry(reg);

		DefaultCamelContext ctx = (DefaultCamelContext)getContext();
		ctx.setRegistry(compositeRegistry);

		/** Exception handling **/

		onException(Exception.class)
			.log(LoggingLevel.ERROR, "ar.com.samsistemas.services.tramites.routebuilder", "NotificadorTramite exception: ${exception}")
			.handled(true)
			.setFaultBody(simple("SolicitadorTramite exception: ${exception}"));

		/** Cron that is being fired after a fixed period **/

		from("timer:foo?period=1m")
			.to("sql:"+getQuery()+"?dataSource=mysqlDS" +
					"&outputClass=ar.com.samsistemas.middleware.notificadortramite.entities.TramiteMailSocioDTO")
			.process(this::processResponseData);

		/** Route than send mail to notify rejected operations **/

		from("direct:sendMail")
			.setHeader("X-Mailtrack-Token", systemProperty(MAIL_ENDPOINT_TOKEN))
			.setHeader("Content-Type", constant("application/json"))
			.to("http4://"+MAIL_ENDPOINT_URL);

	}

	private String getQuery() { //TODO: falta agregar nombre en la db y ultimo cron ejecutado
		return "SELECT socio.nombre, socio.mail, tramite.tipo, tramite.descripcion " +
				"FROM poc_middleware.Socio AS socio " +
				"INNER JOIN poc_middleware.TramiteSocio AS tramite " +
				"ON tramite.id_socio = socio.id " +
				"WHERE tramite.estado = 'RECHAZADO'";
	}

	private void processResponseData(Exchange exchange) {

		List<TramiteMailSocioDTO> response = exchange.getIn().getBody(List.class);

		if(response == null) return; /** If db does not have new data **/

		response.forEach( tramiteMailSocioDTO -> {

			exchange.getOut().setBody(generateMailRequest(tramiteMailSocioDTO));

			ProducerTemplate producerTemplate = exchange.getContext().createProducerTemplate();
			producerTemplate.send("direct:sendMail", exchange);

		});

	}

	private String generateMailRequest(TramiteMailSocioDTO mailData) {

		Map<String, Object> jsonData = new HashMap<>();
		jsonData.put("from_email", "noreply@samsistemas.com.ar");
		jsonData.put("to_email", mailData.getMail());
		jsonData.put("subject", "Notificacion sobre tramite rechazado");

		//TODO: levantar html, reemplazar data y agregarlo
		String initialHtml = "";

		String finalHtml= initialHtml.replace("{nombre_socio}", mailData.getNombre())
								.replace("{tipo_tramite}", mailData.getTipo())
								.replace("{descripcion_tramite}", mailData.getDescripcion());

		jsonData.put("html", finalHtml);

		return new Gson().toJson(jsonData);
	}

}