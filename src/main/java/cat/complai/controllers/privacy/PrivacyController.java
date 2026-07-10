package cat.complai.controllers.privacy;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller serving the privacy policy as a self-contained HTML page.
 *
 * <p>The endpoint is public (excluded from API key auth and rate limiting)
 * and supports three languages via the {@code lang} query parameter:
 * Catalan ({@code ca}, default), Spanish ({@code es}), and English ({@code en}).
 *
 * <p>The HTML page includes a language switcher that reloads the page with
 * the selected language.
 */
@Controller("/privacy")
public class PrivacyController {

    /**
     * Returns the privacy policy HTML page.
     *
     * @param lang the language code: "ca" (Catalan, default), "es" (Spanish), or "en" (English)
     * @return 200 OK with text/html content
     */
    @Get
    public HttpResponse<String> privacy(@QueryValue("lang") String lang) {
        String language = sanitizeLang(lang);
        String html = buildHtml(language);
        return HttpResponse.ok(html).contentType(MediaType.TEXT_HTML);
    }

    private String sanitizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "ca";
        }
        String normalized = lang.trim().toLowerCase();
        if (normalized.equals("es") || normalized.equals("en") || normalized.equals("ca")) {
            return normalized;
        }
        return "ca";
    }

    private String buildHtml(String lang) {
        String title;
        String switcherCa;
        String switcherEs;
        String switcherEn;
        String heading;
        String lastUpdated;
        String introTitle;
        String introText;
        String dataCollectionTitle;
        String dataCollectionText;
        String dataUsageTitle;
        String dataUsageText;
        String dataStorageTitle;
        String dataStorageText;
        String thirdPartyTitle;
        String thirdPartyText;
        String rightsTitle;
        String rightsText;
        String contactTitle;
        String contactText;

        if ("en".equals(lang)) {
            title = "Privacy Policy — ComplAI";
            switcherCa = "Català";
            switcherEs = "Español";
            switcherEn = "English";
            heading = "Privacy Policy";
            lastUpdated = "Last updated: July 2026";
            introTitle = "1. Introduction";
            introText = "ComplAI is a mobile application that helps citizens interact with municipal services using artificial intelligence. This privacy policy explains how we collect, use, and protect your personal data when you use our application.";
            dataCollectionTitle = "2. Data We Collect";
            dataCollectionText = "We collect the following types of data when you use ComplAI:\n• Personal identification: Name, surname, and ID number (provided when generating complaint letters).\n• User feedback: Username, user ID, message content, ratings, and category selections.\n• Chat conversations: Messages exchanged with the AI assistant during your sessions.\n• App preferences: Language selection and city selection (stored locally on your device).";
            dataUsageTitle = "3. How We Use Your Data";
            dataUsageText = "Your data is used for the following purposes:\n• To generate personalized complaint letters with your identification details.\n• To process and respond to your feedback submissions.\n• To maintain conversation context during AI chat sessions.\n• To remember your language and city preferences for a better experience.\n• To improve the quality of our AI services through aggregated, anonymized usage patterns.";
            dataStorageTitle = "4. Data Storage and Security";
            dataStorageText = "• Chat history and sensitive data are stored locally on your device using encrypted storage (Keychain on iOS, EncryptedSharedPreferences on Android).\n• Your language preference is stored locally using standard device storage.\n• Data sent to our servers is processed temporarily for generating responses and is not retained beyond the processing period.\n• We do not sell, share, or rent your personal data to third parties.";
            thirdPartyTitle = "5. Third-Party Services";
            thirdPartyText = "ComplAI uses the following third-party services:\n• Amazon Web Services (AWS): For hosting our API backend and processing requests.\n• OpenRouter: For AI-powered language processing and response generation.\n• Expo: For mobile application distribution and updates.\nEach of these services has its own privacy policy governing how they handle data.";
            rightsTitle = "6. Your Rights";
            rightsText = "You have the following rights regarding your personal data:\n• Access: You can view the data stored on your device within the app.\n• Deletion: You can clear all conversations and app data from the Settings screen.\n• Portability: Your chat history is stored locally and can be accessed through the app.\n• Withdrawal of consent: You can stop using the app at any time, and your local data will remain on your device until you delete it.";
            contactTitle = "7. Contact";
            contactText = "If you have questions about this privacy policy or your personal data, please contact us at:\nEmail: privacy@complai.cat";
        } else if ("es".equals(lang)) {
            title = "Política de Privacidad — ComplAI";
            switcherCa = "Català";
            switcherEs = "Español";
            switcherEn = "English";
            heading = "Política de Privacidad";
            lastUpdated = "Última actualización: julio de 2026";
            introTitle = "1. Introducción";
            introText = "ComplAI es una aplicación móvil que ayuda a los ciudadanos a interactuar con los servicios municipales mediante inteligencia artificial. Esta política de privacidad explica cómo recopilamos, usamos y protegemos sus datos personales cuando utiliza nuestra aplicación.";
            dataCollectionTitle = "2. Datos que Recopilamos";
            dataCollectionText = "Recopilamos los siguientes tipos de datos cuando utiliza ComplAI:\n• Identificación personal: Nombre, apellidos y número de documento de identidad (proporcionados al generar cartas de reclamación).\n• Comentarios del usuario: Nombre de usuario, ID de usuario, contenido del mensaje, puntuaciones y selecciones de categoría.\n• Conversaciones de chat: Mensajes intercambiados con el asistente de IA durante sus sesiones.\n• Preferencias de la aplicación: Selección de idioma y selección de ciudad (almacenadas localmente en su dispositivo).";
            dataUsageTitle = "3. Cómo Usamos Sus Datos";
            dataUsageText = "Sus datos se utilizan para los siguientes fines:\n• Para generar cartas de reclamación personalizadas con sus datos de identificación.\n• Para procesar y responder a sus envíos de comentarios.\n• Para mantener el contexto de conversación durante las sesiones de chat con IA.\n• Para recordar sus preferencias de idioma y ciudad para una mejor experiencia.\n• Para mejorar la calidad de nuestros servicios de IA mediante patrones de uso agregados y anonimizados.";
            dataStorageTitle = "4. Almacenamiento y Seguridad de Datos";
            dataStorageText = "• El historial de chat y los datos sensibles se almacenan localmente en su dispositivo utilizando almacenamiento cifrado (Keychain en iOS, EncryptedSharedPreferences en Android).\n• Su preferencia de idioma se almacena localmente utilizando almacenamiento estándar del dispositivo.\n• Los datos enviados a nuestros servidores se procesan temporalmente para generar respuestas y no se conservan más allá del período de procesamiento.\n• No vendemos, compartimos ni alquilamos sus datos personales a terceros.";
            thirdPartyTitle = "5. Servicios de Terceros";
            thirdPartyText = "ComplAI utiliza los siguientes servicios de terceros:\n• Amazon Web Services (AWS): Para alojar nuestro backend de API y procesar solicitudes.\n• OpenRouter: Para el procesamiento de lenguaje y generación de respuestas con IA.\n• Expo: Para la distribución y actualizaciones de la aplicación móvil.\nCada uno de estos servicios tiene su propia política de privacidad que rige cómo maneja los datos.";
            rightsTitle = "6. Sus Derechos";
            rightsText = "Tiene los siguientes derechos con respecto a sus datos personales:\n• Acceso: Puede ver los datos almacenados en su dispositivo dentro de la aplicación.\n• Eliminación: Puede borrar todas las conversaciones y datos de la aplicación desde la pantalla de Configuración.\n• Portabilidad: Su historial de chat se almacena localmente y se puede acceder a través de la aplicación.\n• Retirada de consentimiento: Puede dejar de usar la aplicación en cualquier momento, y sus datos locales permanecerán en su dispositivo hasta que los elimine.";
            contactTitle = "7. Contacto";
            contactText = "Si tiene preguntas sobre esta política de privacidad o sus datos personales, por favor contáctenos en:\nCorreo electrónico: privacy@complai.cat";
        } else {
            title = "Política de Privacitat — ComplAI";
            switcherCa = "Català";
            switcherEs = "Español";
            switcherEn = "English";
            heading = "Política de Privacitat";
            lastUpdated = "Última actualització: juliol de 2026";
            introTitle = "1. Introducció";
            introText = "ComplAI és una aplicació mòbil que ajuda els ciutadans a interactuar amb els serveis municipals mitjançant intel·ligència artificial. Aquesta política de privacitat explica com recollim, utilitzem i protegim les vostres dades personals quan utilitzeu la nostra aplicació.";
            dataCollectionTitle = "2. Dades que Recollim";
            dataCollectionText = "Recollim els següents tipus de dades quan utilitzeu ComplAI:\n• Identificació personal: Nom, cognoms i número de document d'identitat (proporcionats en generar cartes de reclamació).\n• Comentaris de l'usuari: Nom d'usuari, ID d'usuari, contingut del missatge, puntuacions i seleccions de categoria.\n• Converses de xat: Missatges intercanviats amb l'assistent d'IA durant les vostres sessions.\n• Preferències de l'aplicació: Selecció d'idioma i selecció de ciutat (emmagatzemades localment al vostre dispositiu).";
            dataUsageTitle = "3. Com Utilitzem Les Vostres Dades";
            dataUsageText = "Les vostres dades s'utilitzen per als següents fins:\n• Per generar cartes de reclamació personalitzades amb les vostres dades d'identificació.\n• Per processar i respondre als vostres enviaments de comentaris.\n• Per mantenir el context de conversa durant les sessions de xat amb IA.\n• Per recordar les vostres preferències d'idioma i ciutat per a una millor experiència.\n• Per millorar la qualitat dels nostres serveis d'IA mitjançant patrons d'ús agregats i anonimitzats.";
            dataStorageTitle = "4. Emmagatzematge i Seguretat de Dades";
            dataStorageText = "• L'historial de xat i les dades sensibles s'emmagatzemen localment al vostre dispositiu utilitzant emmagatzematge xifrat (Keychain a iOS, EncryptedSharedPreferences a Android).\n• La vostra preferència d'idioma s'emmagatzema localment utilitzant emmagatzematge estàndard del dispositiu.\n• Les dades enviades als nostres servidors es processen temporalment per generar respostes i no es conserven més enllà del període de processament.\n• No venem, compartim ni lloguem les vostres dades personals a tercers.";
            thirdPartyTitle = "5. Serveis de Tercers";
            thirdPartyText = "ComplAI utilitza els següents serveis de tercers:\n• Amazon Web Services (AWS): Per allotjar el nostre backend d'API i processar sol·licituds.\n• OpenRouter: Per al processament de llenguatge i generació de respostes amb IA.\n• Expo: Per a la distribució i actualitzacions de l'aplicació mòbil.\nCadascun d'aquests serveis té la seva pròpia política de privacitat que regeix com gestiona les dades.";
            rightsTitle = "6. Els Vostres Drets";
            rightsText = "Teniu els següents drets pel que fa a les vostres dades personals:\n• Accés: Podeu veure les dades emmagatzemades al vostre dispositiu dins de l'aplicació.\n• Eliminació: Podeu esborrar totes les converses i dades de l'aplicació des de la pantalla de Configuració.\n• Portabilitat: El vostre historial de xat s'emmagatzema localment i shi pot accedir a través de l'aplicació.\n• Retirada de consentiment: Podeu deixar dutilitzar laplicació en qualsevol moment, i les vostres dades locals romandran al vostre dispositiu fins que les elimineu.";
            contactTitle = "7. Contacte";
            contactText = "Si teniu preguntes sobre aquesta política de privacitat o les vostres dades personals, poseu-vos en contacte amb nosaltres a:\nCorreu electrònic: privacy@complai.cat";
        }

        // Build active language indicator
        String activeCa = "ca".equals(lang) ? "font-weight:700;text-decoration:underline;" : "opacity:0.6;";
        String activeEs = "es".equals(lang) ? "font-weight:700;text-decoration:underline;" : "opacity:0.6;";
        String activeEn = "en".equals(lang) ? "font-weight:700;text-decoration:underline;" : "opacity:0.6;";

        return """
                <!DOCTYPE html>
                <html lang="%s">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            line-height: 1.6;
                            color: #1a1a2e;
                            background: #f8f9fa;
                            padding: 20px;
                            max-width: 800px;
                            margin: 0 auto;
                        }
                        .header {
                            text-align: center;
                            padding: 30px 0 20px;
                            border-bottom: 2px solid #e0e0e0;
                            margin-bottom: 30px;
                        }
                        .header h1 {
                            font-size: 28px;
                            color: #1a1a2e;
                            margin-bottom: 8px;
                        }
                        .header .date {
                            font-size: 14px;
                            color: #666;
                        }
                        .lang-switcher {
                            display: flex;
                            justify-content: center;
                            gap: 16px;
                            margin: 16px 0;
                        }
                        .lang-switcher a {
                            color: #4361ee;
                            text-decoration: none;
                            font-size: 14px;
                            padding: 6px 12px;
                            border-radius: 6px;
                            transition: background 0.2s;
                        }
                        .lang-switcher a:hover {
                            background: rgba(67, 97, 238, 0.1);
                        }
                        .lang-switcher a.active {
                            background: #4361ee;
                            color: white;
                        }
                        .section {
                            margin-bottom: 28px;
                        }
                        .section h2 {
                            font-size: 20px;
                            color: #1a1a2e;
                            margin-bottom: 12px;
                            padding-bottom: 8px;
                            border-bottom: 1px solid #e0e0e0;
                        }
                        .section p {
                            font-size: 15px;
                            color: #333;
                            white-space: pre-line;
                        }
                        .footer {
                            text-align: center;
                            padding-top: 30px;
                            border-top: 2px solid #e0e0e0;
                            margin-top: 30px;
                            font-size: 13px;
                            color: #888;
                        }
                    </style>
                </head>
                <body>
                    <div class="header">
                        <h1>%s</h1>
                        <div class="date">%s</div>
                        <div class="lang-switcher">
                            <a href="?lang=ca" style="%s">%s</a>
                            <a href="?lang=es" style="%s">%s</a>
                            <a href="?lang=en" style="%s">%s</a>
                        </div>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="section">
                        <h2>%s</h2>
                        <p>%s</p>
                    </div>

                    <div class="footer">
                        <p>ComplAI &copy; 2026</p>
                    </div>
                </body>
                </html>
                """.formatted(
                lang,
                title,
                heading,
                lastUpdated,
                activeCa, switcherCa,
                activeEs, switcherEs,
                activeEn, switcherEn,
                introTitle, introText,
                dataCollectionTitle, dataCollectionText,
                dataUsageTitle, dataUsageText,
                dataStorageTitle, dataStorageText,
                thirdPartyTitle, thirdPartyText,
                rightsTitle, rightsText,
                contactTitle, contactText);
    }
}
