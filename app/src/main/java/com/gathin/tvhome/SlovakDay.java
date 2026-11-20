package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Home's date in Slovak ("piatok 20. novembra") and today's name day from the Slovak calendar (meniny), for
 * config.xml slovak_date (2026-11-20). Names are spelled out here rather than taken from the TV's locale, which is
 * Czech on this Streamer.
 */
final class SlovakDay {

    private SlovakDay() {
    }

    private static final String[] WEEKDAY = {"pondelok", "utorok", "streda", "štvrtok", "piatok", "sobota", "nedeľa"};
    /** Month names as used after a day number (genitive): "20. novembra". */
    private static final String[] MONTH = {"januára", "februára", "marca", "apríla", "mája", "júna", "júla",
            "augusta", "septembra", "októbra", "novembra", "decembra"};

    /** "piatok 20. novembra". */
    static String date(LocalDate d) {
        return WEEKDAY[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + ". " + MONTH[d.getMonthValue() - 1];
    }

    /** Who has the name day on d, or "" (1 Jan, 1 May, 2 Nov and 25 Dec have none). */
    static String nameDay(LocalDate d) {
        String[] month = NAMES[d.getMonthValue() - 1].split("\\|", -1);
        int i = d.getDayOfMonth() - 1;
        return i < month.length ? month[i] : "";
    }

    /** The Slovak name-day calendar, one string per month, days separated by "|". */
    private static final String[] NAMES = {
            // január
            "|Alexandra, Karina|Daniela|Drahoslav|Andrea|Antónia|Bohuslava|Severín|Alexej|Dáša|Malvína|Ernest"
                    + "|Rastislav|Radovan|Dobroslav|Kristína|Nataša|Bohdana|Drahomíra, Mário|Dalibor|Vincent|Zora"
                    + "|Miloš|Timotej|Gejza|Tamara|Bohuš|Alfonz|Gašpar|Ema|Emil",
            // február
            "Tatiana|Erika, Erik|Blažej|Veronika|Agáta|Dorota|Vanda|Zoja|Zdenko|Gabriela|Dezider|Perla|Arpád"
                    + "|Valentín|Pravoslav|Ida, Liana|Miloslava|Jaromír|Vlasta|Lívia|Eleonóra|Etela"
                    + "|Roman, Romana|Matej|Frederik, Frederika|Viktor|Alexander|Zlatica|Radomír",
            // marec
            "Albín|Anežka|Bohumil, Bohumila|Kazimír|Fridrich|Radoslav, Radoslava|Tomáš|Alan, Alana|Františka"
                    + "|Branislav, Bruno|Angela, Angelika|Gregor|Vlastimil|Matilda|Svetlana|Boleslav|Ľubica"
                    + "|Eduard|Jozef|Víťazoslav, Klaudius|Blahoslav|Beňadik|Adrián|Gabriel|Marián|Emanuel"
                    + "|Alena|Soňa|Miroslav|Vieroslava|Benjamín",
            // apríl
            "Hugo|Zita|Richard|Izidor|Miroslava|Irena|Zoltán|Albert|Milena|Igor|Július|Estera|Aleš|Justína"
                    + "|Fedor|Dana, Danica|Rudolf, Rudolfa|Valér|Jela|Marcel|Ervín|Slavomír|Vojtech|Juraj"
                    + "|Marek|Jaroslava|Jaroslav|Jarmila|Lea|Anastázia",
            // máj
            "|Žigmund|Galina, Timea|Florián|Lesana, Lesia|Hermína|Monika|Ingrida|Roland|Viktória|Blažena"
                    + "|Pankrác|Servác|Bonifác|Žofia, Sofia|Svetozár|Gizela, Aneta|Viola|Gertrúda|Bernard"
                    + "|Zina|Júlia, Juliana|Želmíra|Ela|Urban, Vivien|Dušan|Iveta|Viliam|Vilma|Ferdinand"
                    + "|Petrana, Petronela",
            // jún
            "Žaneta|Xénia, Oxana|Karolína|Lenka|Laura|Norbert|Róbert, Robert|Medard|Stanislava|Margaréta"
                    + "|Dobroslava|Zlatko|Anton|Vasil|Vít|Blanka, Bianka|Adolf|Vratislav|Alfréd|Valéria"
                    + "|Alojz|Paulína|Sidónia|Ján|Olívia, Tadeáš|Adriána|Ladislav, Ladislava|Beáta"
                    + "|Peter, Pavol, Petra|Melánia",
            // júl
            "Diana|Berta|Miloslav|Prokop|Cyril, Metod|Patrik, Patrícia|Oliver|Ivan|Lujza|Amália|Milota|Nina"
                    + "|Margita|Kamil|Henrich|Drahomír, Rút|Bohuslav|Kamila|Dušana|Iľja, Eliáš|Daniel"
                    + "|Magdaléna|Oľga|Vladimír|Jakub, Timur|Anna, Hana, Anita|Božena|Krištof|Marta|Libuša"
                    + "|Ignác",
            // august
            "Božidara|Gustáv|Jerguš|Dominik, Dominika|Hortenzia|Jozefína|Štefánia|Oskar|Ľubomíra|Vavrinec"
                    + "|Zuzana|Darina|Ľubomír|Mojmír|Marcela|Leonard|Milica|Elena, Helena|Lýdia"
                    + "|Anabela, Liliana|Jana|Tichomír|Filip|Bartolomej|Ľudovít|Samuel|Silvia|Augustín"
                    + "|Nikola, Nikolaj|Ružena|Nora",
            // september
            "Drahoslava|Linda, Rebeka|Belo|Rozália|Regína|Alica|Marianna|Miriama|Martina|Oleg|Bystrík"
                    + "|Mária, Marlena|Ctibor|Ľudomil|Jolana|Ľudmila|Olympia|Eugénia|Konštantín"
                    + "|Ľuboslav, Ľuboslava|Matúš|Móric|Zdenka|Ľuboš, Ľubor|Vladislav, Vladislava|Edita"
                    + "|Cyprián|Václav|Michal, Michaela|Jarolím",
            // október
            "Arnold|Levoslav|Stela|František|Viera|Natália|Eliška|Brigita|Dionýz|Slavomíra|Valentína"
                    + "|Maximilián|Koloman|Boris|Terézia|Vladimíra|Hedviga|Lukáš|Kristián|Vendelín|Uršuľa"
                    + "|Sergej|Alojzia|Kvetoslava|Aurel|Demeter|Sabína|Dobromila|Klára|Šimon, Simona"
                    + "|Aurélia",
            // november
            "Denis, Denisa||Hubert|Karol|Imrich|Renáta|René|Bohumír|Teodor|Tibor|Martin, Maroš|Svätopluk"
                    + "|Stanislav|Irma|Leopold|Agnesa|Klaudia|Eugen|Alžbeta|Félix|Elvíra|Cecília|Klement"
                    + "|Emília|Katarína|Kornel|Milan|Henrieta|Vratko|Ondrej, Andrej",
            // december
            "Edmund|Bibiána|Oldrich|Barbora, Barbara|Oto|Mikuláš|Ambróz|Marína|Izabela|Radúz|Hilda|Otília"
                    + "|Lucia|Branislava, Bronislava|Ivica|Albína|Kornélia|Sláva, Slávka|Judita|Dagmara"
                    + "|Bohdan|Adela|Nadežda|Adam, Eva||Štefan|Filoména|Ivana, Ivona|Milada|Dávid|Silvester",
    };
}
