package com.gocavgo.ussdservice.service;

import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class LanguageService {

    private final Map<String, Map<String, String>> messages;

    public LanguageService() {
        this.messages = new HashMap<>();
        initializeMessages();
    }

    private void initializeMessages() {
        // English messages
        Map<String, String> englishMessages = new HashMap<>();
        englishMessages.put("welcome", "CON Welcome! Please select your language:\n1. English\n2. Kinyarwanda\n3. French");
        englishMessages.put("book_now", "CON GoCavGo - Book Your Trip:\n1. Book Trip\n2. Change Language\n3. Help");
        englishMessages.put("enter_origin", "CON Enter your origin location:");
        englishMessages.put("enter_destination", "CON Enter your destination:");
        englishMessages.put("available_trips", "CON Available Trips:");
        englishMessages.put("select_trip", "CON Select a trip by number:");
        englishMessages.put("trip_selected", "END Trip booked successfully follow payment instructions sent!\nFrom: %s\nTo: %s\nPrice: %s\nThank you for using GoCavGo!");
        englishMessages.put("no_trips_found", "CON No trips found for this route.");
        englishMessages.put("search_error", "CON Sorry, we couldn't search for trips right now. Please try again later.");
        englishMessages.put("invalid_origin", "CON Please enter a valid origin location.");
        englishMessages.put("invalid_destination", "CON Please enter a valid destination.");
        englishMessages.put("invalid_selection", "CON Invalid selection. Please choose a valid trip number.");
        englishMessages.put("help", "END Help: Use this service to book trips between different locations. Follow the prompts to search and select your preferred trip.");
        englishMessages.put("invalid_option", "CON Invalid option. Please try again:");
        englishMessages.put("goodbye", "END Thank you for using GoCavGo!");
        englishMessages.put("session_expired", "END Your session has expired. Please start over ");

        // Kinyarwanda messages
        Map<String, String> kinyarwandaMessages = new HashMap<>();
        kinyarwandaMessages.put("welcome", "CON Murakaza neza! Hitamo ururimi rwawe:\n1. Icyongereza\n2. Ikinyarwanda\n3. Igifaransa");
        kinyarwandaMessages.put("book_now", "CON GoCavGo - Andika Urugendo Rwawe:\n1. Andika Urugendo\n2. Hindura Ururimi\n3. Ubufasha");
        kinyarwandaMessages.put("enter_origin", "CON Andika aho uva:");
        kinyarwandaMessages.put("enter_destination", "CON Andika aho ujya:");
        kinyarwandaMessages.put("available_trips", "CON Ingendo Ziboneka:");
        kinyarwandaMessages.put("select_trip", "CON Hitamo urugendo ukoresha numero:");
        kinyarwandaMessages.put("trip_selected", "END Gutega byagenze neza Kurikiza amaabwiriza yo kwishyura ugiye kwakira!\nUva: %s\nUgiye: %s\nIgiciro: %s\nUrakoze gukoresha GoCavGo!");
        kinyarwandaMessages.put("no_trips_found", "CON Nta rugendo rwaboneka kuri ubu muhanda.");
        kinyarwandaMessages.put("search_error", "CON Ihangane, tutashobora gushaka ingendo ubu. Ongera ugerageze nyuma.");
        kinyarwandaMessages.put("invalid_origin", "CON Nyamuneka andika aho uva neza.");
        kinyarwandaMessages.put("invalid_destination", "CON Nyamuneka andika aho ujya neza.");
        kinyarwandaMessages.put("invalid_selection", "CON Ihitamo ridafite. Hitamo numero y'urugendo rufite.");
        kinyarwandaMessages.put("help", "END Ubufasha: Koresha iki gikorwa kugira ngo wandike ingendo hagati y'ahantu hatandukanye. Kurikiza ibikubiye kugira ngo ushake kandi uhitamo urugendo rwawe.");
        kinyarwandaMessages.put("invalid_option", "CON Ihitamo ridafite. Ongera ugerageze:");
        kinyarwandaMessages.put("goodbye", "END Urakoze gukoresha GoCavGo!");
        kinyarwandaMessages.put("session_expired", "END Igihe cyawe cyarangiye. tangira bushya.");

        // French messages
        Map<String, String> frenchMessages = new HashMap<>();
        frenchMessages.put("welcome", "CON Bienvenue! Veuillez sélectionner votre langue:\n1. Anglais\n2. Kinyarwanda\n3. Français");
        frenchMessages.put("book_now", "CON GoCavGo - Réservez Votre Voyage:\n1. Réserver Voyage\n2. Changer Langue\n3. Aide");
        frenchMessages.put("enter_origin", "CON Entrez votre lieu de départ:");
        frenchMessages.put("enter_destination", "CON Entrez votre destination:");
        frenchMessages.put("available_trips", "CON Voyages Disponibles:");
        frenchMessages.put("select_trip", "CON Sélectionnez un voyage par numéro:");
        frenchMessages.put("trip_selected", "END Voyage réservé avec succès!\nDe: %s\nVers: %s\nPrix: %s\nMerci d'utiliser GoCavGo!");
        frenchMessages.put("no_trips_found", "CON Aucun voyage trouvé pour cette route.");
        frenchMessages.put("search_error", "CON Désolé, nous ne pouvons pas rechercher de voyages pour le moment. Veuillez réessayer plus tard.");
        frenchMessages.put("invalid_origin", "CON Veuillez entrer un lieu de départ valide.");
        frenchMessages.put("invalid_destination", "CON Veuillez entrer une destination valide.");
        frenchMessages.put("invalid_selection", "CON Sélection invalide. Veuillez choisir un numéro de voyage valide.");
        frenchMessages.put("help", "END Aide: Utilisez ce service pour réserver des voyages entre différents lieux. Suivez les instructions pour rechercher et sélectionner votre voyage préféré.");
        frenchMessages.put("invalid_option", "CON Option invalide. Veuillez réessayer:");
        frenchMessages.put("goodbye", "END Merci d'utiliser GoCavGo!");
        frenchMessages.put("session_expired", "END Votre session a expiré. Veuillez recommencer.");

        messages.put("en", englishMessages);
        messages.put("rw", kinyarwandaMessages);
        messages.put("fr", frenchMessages);
    }

    public String getMessage(String language, String key, Object... args) {
        String message = messages.getOrDefault(language, messages.get("en"))
                .getOrDefault(key, messages.get("en").get(key));
        return args.length > 0 ? String.format(message, args) : message;
    }

    public String getLanguageFromChoice(String choice) {
        return switch (choice) {
            case "1" -> "en";
            case "2" -> "rw";
            case "3" -> "fr";
            default -> "en";
        };
    }
}