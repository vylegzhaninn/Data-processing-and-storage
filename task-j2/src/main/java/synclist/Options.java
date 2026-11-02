package synclist;

/**
 * Параметры запуска программы, разбираемые из аргументов командной строки.
 *
 * @param impl       реализация списка: {@code own} — собственный двусвязный
 *                   список, {@code sync-array} — {@code ArrayList} с обёрткой
 *                   {@code Collections.synchronizedList()}
 * @param threads    число дочерних сортирующих нитей
 * @param betweenMs  задержка между шагами сортировки, мс
 * @param insideMs   задержка внутри шага сортировки, мс
 * @param demo       {@code true} — демонстрационный (измерительный) режим
 * @param durationMs длительность демонстрационного прогона, мс
 * @param elements   число записей в демонстрационном прогоне
 */
record Options(Options.Impl impl, int threads, long betweenMs, long insideMs,
               boolean demo, long durationMs, int elements) {

    /** Текст справки по аргументам командной строки. */
    static final String USAGE = """
            Использование: java -cp ... synclist.Main [опции]
              --impl=own|sync-array  реализация списка (по умолчанию own)
              --threads=N            число сортирующих нитей (по умолчанию 3)
              --delay=MS             задержка между шагами сортировки (по умолчанию 1000)
              --inside=MS            задержка внутри шага сортировки (по умолчанию 1000)
              --demo                 измерительный режим без ввода пользователя
              --duration=MS          длительность демо-прогона (по умолчанию 10000)
              --elements=N           число записей в демо-прогоне (по умолчанию 24)""";

    /** Реализации списка. */
    enum Impl {

        /** Собственный двусвязный список с переустановкой ссылок. */
        OWN,

        /** Стандартный ArrayList с обёрткой Collections.synchronizedList. */
        SYNC_ARRAY;

        /**
         * Создаёт экземпляр разделяемого состояния для реализации.
         *
         * @return новое разделяемое состояние
         */
        SharedState newState() {
            return this == OWN ? new LinkedStringState() : new SyncListState();
        }

        @Override
        public String toString() {
            return this == OWN ? "own" : "sync-array";
        }
    }

    /**
     * Разбирает аргументы командной строки, применяя значения по умолчанию.
     *
     * @param args аргументы вида {@code --флаг=значение}
     * @return разобранные параметры
     * @throws IllegalArgumentException при неизвестном флаге или плохом значении
     */
    static Options parse(String[] args) {
        Impl impl = Impl.OWN;
        int threads = 3;
        long between = 1000;
        long inside = 1000;
        boolean demo = false;
        long duration = 10_000;
        int elements = 24;

        for (String arg : args) {
            if (arg.startsWith("--impl=")) {
                impl = switch (arg.substring("--impl=".length())) {
                    case "own" -> Impl.OWN;
                    case "sync-array" -> Impl.SYNC_ARRAY;
                    default -> throw new IllegalArgumentException("Неизвестная реализация: " + arg + "\n" + USAGE);
                };
            } else if (arg.startsWith("--threads=")) {
                threads = parseInt(arg, "--threads=");
            } else if (arg.startsWith("--delay=")) {
                between = parseLong(arg, "--delay=");
            } else if (arg.startsWith("--inside=")) {
                inside = parseLong(arg, "--inside=");
            } else if (arg.equals("--demo")) {
                demo = true;
            } else if (arg.startsWith("--duration=")) {
                duration = parseLong(arg, "--duration=");
            } else if (arg.startsWith("--elements=")) {
                elements = parseInt(arg, "--elements=");
            } else {
                throw new IllegalArgumentException("Неизвестный аргумент: " + arg + "\n" + USAGE);
            }
        }
        if (threads < 1 || between < 0 || inside < 0 || duration < 1 || elements < 0) {
            throw new IllegalArgumentException("Недопустимые значения аргументов\n" + USAGE);
        }
        return new Options(impl, threads, between, inside, demo, duration, elements);
    }

    private static int parseInt(String arg, String prefix) {
        try {
            return Integer.parseInt(arg.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ожидалось целое число: " + arg + "\n" + USAGE);
        }
    }

    private static long parseLong(String arg, String prefix) {
        try {
            return Long.parseLong(arg.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ожидалось целое число: " + arg + "\n" + USAGE);
        }
    }
}
