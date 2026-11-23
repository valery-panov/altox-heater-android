# Коды ошибок Webasto

На команду `READ` Altox присылает память ошибок отопителя:

```
Webasto diagnostics
Fault code: 84, 12
```

или, если она пуста:

```
Webasto diagnostics
Fault code: none
```

Коды шестнадцатеричные, как в Webasto Thermo Test (WTT). Первым идёт самый свежий. `00` и `FF` —
пустые ячейки памяти, приложение их не показывает. `READ` коды не стирает, это делает `ERASE`.
Приложение запоминает результат последнего `READ` и помечает коды, которых в нём не было,
как «(новая)». Тот же код, записанный заново, так не пометить: `READ` не говорит, когда он записан.

## Когда приложение шлёт READ и ERASE

- `READ` — один раз на ошибку, если отопитель ответил, но повёл себя не так: не запустился, не
  разгорелся после повторной проверки, выключился без пламени или раньше срока, не выключился в
  конце цикла или после `STOP`. Коды показываются под сообщением об ошибке. Если ответа нет за
  таймаут ожидания, строка пропадает; ошибкой это не считается. «Нет ответа» и «SMS не
  отправлено» к `READ` не ведут: модуль, который молчит на `STATUS`, промолчит и на `READ`.
- `READ` — кнопка «Прочитать коды ошибок» в настройках. Коды показываются под кнопкой, новые
  по сравнению с прошлым `READ` помечены.
- `ERASE`, через 10 с `READ` — кнопка «Стереть коды ошибок» в настройках. `READ` проверяет, что
  память пуста.

## Таблица

Источники:

- **Altox** — [altox.ru/docs/webasto-code](https://altox.ru/docs/webasto-code), полная таблица
  кодов W-Bus с рекомендациями. Основа таблицы.
- **Evo** — руководство по ремонту Webasto Thermo Top Evo, разделы 5.5 и 5.6. Взяты коды, для
  которых у Altox только «Fault NN» (`4E`, `86`, `A9`), и `40`. Само руководство в репозиторий
  не входит по лицензионным причинам.

«В приложении» — текст из `Faults.kt`. «Что проверить» — суть рекомендации источника, если она
есть; «как для 02» — забор воздуха и выхлоп, топливная система, насос-дозатор, свеча накаливания.
Коды, которых нет в таблице («Fault NN» у Altox), приложение показывает без расшифровки, кроме
диапазонов `64`–`7E` и `E0`–`FE`: это «ошибка производителя авто» 5–31 и 33–63.

| Hex | Dec | Webasto | В приложении | Что проверить | Источник |
|---|---|---|---|---|---|
| 01 | 1 | Defective control unit | неисправен блок управления или датчик температуры ОЖ | стереть ошибку, снять блокировку, запустить снова | Altox |
| 02 | 2 | No start | нет запуска: пламя не появилось за отведённое время | забор воздуха и выхлоп, топливная система, насос-дозатор, свеча накаливания | Altox |
| 03 | 3 | Flame failure | срыв пламени |  | Altox |
| 04 | 4 | Supply voltage too high | напряжение питания слишком высокое | бортсеть | Altox |
| 05 | 5 | Flame was detected prior to combustion | пламя обнаружено до подачи топлива | забор воздуха и выхлоп, топливная система, свеча накаливания | Altox |
| 06 | 6 | Heating unit overheated | перегрев отопителя | контур ОЖ, циркуляционный насос, датчики температуры, течи | Altox |
| 07 | 7 | Heating unit interlocked | отопитель заблокирован |  | Altox |
| 08 | 8 | Metering pump short circuit | замыкание топливного насоса-дозатора | электрика топливоподачи | Altox |
| 09 | 9 | Combustion air fan short circuit | замыкание вентилятора камеры сгорания |  | Altox |
| 0A | 10 | Glow plug / flame monitor short circuit | замыкание свечи накаливания или датчика пламени |  | Altox |
| 0B | 11 | Circulation pump short circuit | замыкание циркуляционного насоса или перегрузка его мотора | электрика контура ОЖ | Altox |
| 0C | 12 | No communication to air condition | нет связи с блоком кондиционера |  | Altox |
| 0D | 13 | Green LED short circuit | замыкание зелёного светодиода |  | Altox |
| 0E | 14 | Yellow LED short circuit | замыкание жёлтого светодиода |  | Altox |
| 0F | 15 | No configuration signal | нет сигнала конфигурации |  | Altox |
| 10 | 16 | Solenoid valve short circuit | замыкание клапана переключения ОЖ | электрика клапана | Altox |
| 11 | 17 | ECU wrong coded | блок управления закодирован неверно (не тот вид топлива) | только в авто с CAN/LIN: тип отопителя и вид топлива | Altox |
| 12 | 18 | W-Bus communication failure | сбой связи по W-Bus | разово безвредна; если больше 10 раз и есть сбои в работе — проводка W-Bus | Altox |
| 13 | 19 | Vehicle fan relay short circuit | замыкание реле вентилятора салона | вентилятор автомобиля | Altox |
| 14 | 20 | Temperature sensor short circuit | замыкание датчика температуры ОЖ |  | Altox |
| 15 | 21 | Combustion air fan blocked | вентилятор камеры сгорания заблокирован | вентилятор, засор воздухозаборника | Altox |
| 16 | 22 | Battery main switch short circuit | замыкание главного выключателя питания |  | Altox |
| 17 | 23 | Invalid air flow reduction | ошибка снижения потока воздуха |  | Altox |
| 18 | 24 | Communication failure on customer specific bus | сбой связи на шине автомобиля |  | Altox |
| 19 | 25 | Glow plug / electronic ignition short circuit | замыкание свечи накаливания или электронного поджига | электрика свечи накаливания | Altox |
| 1A | 26 | Flame sensor short circuit | замыкание датчика пламени |  | Altox |
| 1B | 27 | Overheat short circuit | замыкание датчика перегрева | электрика датчиков температуры | Altox |
| 1D | 29 | Solenoid valve shed test short circuit | замыкание электромагнитного клапана при проверке |  | Altox |
| 1E | 30 | Fuel sensor short circuit | замыкание датчика топлива |  | Altox |
| 1F | 31 | Nozzle stock heating short circuit | замыкание подогрева топливной форсунки |  | Altox |
| 20 | 32 | Operation indicator short circuit | замыкание индикатора работы |  | Altox |
| 21 | 33 | Flame indicator short circuit | замыкание индикатора пламени |  | Altox |
| 22 | 34 | Reference resistance wrong | неверное образцовое сопротивление |  | Altox |
| 23 | 35 | Crash interlock activated | сработала блокировка при аварии |  | Altox |
| 24 | 36 | Car is almost out of fuel | в баке почти нет топлива |  | Altox |
| 25 | 37 | Fuel pre heating short circuit | замыкание предпрогрева топлива |  | Altox |
| 26 | 38 | PCB temperature sensor short circuit | замыкание датчика температуры блока управления |  | Altox |
| 27 | 39 | Ground contact to the ECU broken | нет массы блока управления |  | Altox |
| 28 | 40 | Board net energy manager low power voltage | низкое напряжение по данным энергоменеджера автомобиля |  | Altox |
| 29 | 41 | Fuel priming still not done | топливо ещё не прокачано |  | Altox |
| 2A | 42 | Error in the radio telegram | ошибка в радиопакете |  | Altox |
| 2B | 43 | Telestart still not programmed | Telestart ещё не запрограммирован |  | Altox |
| 2C | 44 | The pressure sensor has short circuit | замыкание датчика давления |  | Altox |
| 2D | 45 | The combustion air fan power circuit is defective | неисправна цепь вентилятора горения: обороты ниже нормы | стереть ошибку, снять блокировку, запустить снова | Altox |
| 2E | 46 | The glow / ignition element power circuit is defective | неисправна цепь свечи накаливания: сопротивление вне нормы | если больше 3 раз: забор воздуха и выхлоп, цепь свечи накаливания | Altox |
| 2F | 47 | Flame abort | срыв пламени, будет повторный пуск | если больше 10 раз: как для 02 | Altox |
| 32 | 50 | No start from control idle period | нет запуска после регулировочной паузы |  | Altox |
| 33 | 51 | Flame monitor signal invalid | неверный сигнал датчика пламени |  | Altox |
| 34 | 52 | Default values entered | введены значения по умолчанию |  | Altox |
| 35 | 53 | EOL programming has not been carried out | не выполнено программирование EOL |  | Altox |
| 36 | 54 | Thermal fuse short circuit | замыкание термопредохранителя |  | Altox |
| 37 | 55 | Coolant temperature during initial start-up too high | слишком горячая ОЖ при первом запуске | только при первом запуске на заводе | Altox |
| 38 | 56 | Initial starting attempt failed | первая попытка запуска не удалась | если больше 10 раз: как для 02 | Altox |
| 39 | 57 | Initial starting attempt failed, not restarting | первая попытка запуска не удалась, без повтора | если больше 3 раз: как для 02 | Altox |
| 3A | 58 | W-Bus / LIN-bus short circuit to earth | замыкание W-Bus или LIN на массу | проводка W-Bus | Altox |
| 3C | 60 | Internal control unit error 60 | внутренняя ошибка блока управления 60 | стереть ошибку, снять блокировку, запустить снова | Altox |
| 3D | 61 | Internal control unit error 61 | внутренняя ошибка блока управления 61 | стереть ошибку, снять блокировку, запустить снова | Altox |
| 3E | 62 | Internal control unit error 62 | внутренняя ошибка блока управления 62 | стереть ошибку, снять блокировку, запустить снова | Altox |
| 3F | 63 | Wrong version dataset loaded | загружена неверная версия программы | стереть ошибку, снять блокировку, запустить снова | Altox |
| 40 | 64 | Glow plug / electronic ignition unit: glow filament interruption | обрыв нити свечи накаливания | свеча накаливания; замена блока управления или отопителя | Evo |
| 45 | 69 | Short circuit / open circuit of heating relay | обрыв или замыкание реле нагревателя |  | Altox |
| 4C | 76 | Overvoltage component protection | отключение из-за сильного перенапряжения | бортсеть | Altox |
| 4E | 78 | Customer specific fault 3 | ошибка производителя авто 3 | если больше 3 раз: как для 02 | Evo |
| 50 | 80 | User interface idle mode (no communication) | нет связи с пультом |  | Altox |
| 51 | 81 | User interface has communication fault | ошибка связи пульта |  | Altox |
| 52 | 82 | User interface sends no defined operating mode | пульт прислал неизвестный режим работы |  | Altox |
| 53 | 83 | Heater fan status message negative | вентилятор отопителя сообщил об ошибке |  | Altox |
| 54 | 84 | Heater fan status bus has short circuit to UB | замыкание шины вентилятора отопителя на плюс питания |  | Altox |
| 55 | 85 | Temperature water sensor failure | неисправен датчик температуры ОЖ |  | Altox |
| 56 | 86 | Temperature water sensor short circuit to UB | замыкание датчика температуры ОЖ на плюс питания |  | Altox |
| 57 | 87 | Overheating water temperature sensor | перегрев по датчику температуры ОЖ |  | Altox |
| 58 | 88 | Overstepping water temperature sensor gradient | температура ОЖ меняется слишком быстро |  | Altox |
| 59 | 89 | Overheating blow temperature sensor | перегрев по датчику температуры воздуха |  | Altox |
| 5A | 90 | Overstepping low temperature sensor gradient | неверный градиент датчика температуры |  | Altox |
| 5B | 91 | Overheating PCB temperature sensor | перегрев блока управления |  | Altox |
| 5C | 92 | Overstepping PCB temperature sensor gradient | неверный градиент датчика температуры блока управления |  | Altox |
| 5D | 93 | Cabin temperature sensor failure | неисправен датчик температуры салона |  | Altox |
| 5E | 94 | Flame detector gradient failure | неверный градиент датчика пламени |  | Altox |
| 5F | 95 | Emergency cooling | аварийное охлаждение |  | Altox |
| 60 | 96 | Customer specific fault 1 | ошибка производителя авто 1 |  | Altox |
| 61 | 97 | Customer specific fault 2 | ошибка производителя авто 2 |  | Altox |
| 62 | 98 | Customer specific fault 3 | переполнение таймера DP_max | если больше 3 раз: как для 02 | Altox |
| 63 | 99 | Customer specific fault 4 | ошибка производителя авто 4 |  | Altox |
| 7F | 127 | Customer specific fault 32 | ошибка производителя авто 32 |  | Altox |
| 81 | 129 | EOL checksum error | ошибка контрольной суммы EOL | стереть ошибку, снять блокировку, запустить снова | Altox |
| 82 | 130 | No start during test run | нет запуска при тестовом прогоне | только при тестовом запуске на заводе | Altox |
| 83 | 131 | Flame failure | пламя гасло больше допустимого числа раз | если больше 3 раз: как для 02 | Altox |
| 84 | 132 | Operating voltage too low | напряжение питания слишком низкое | не дольше ли нагрев, чем поездки; бортсеть | Altox |
| 85 | 133 | Flame was detected after combustion | пламя обнаружено после окончания горения |  | Altox |
| 86 | 134 | Excessive water temperature without combustion | перегрев ОЖ без горения (выше 145 °C) | если больше 3 раз: контур ОЖ и его прокачка, датчики температуры, замена отопителя | Evo |
| 87 | 135 | Heater lock-out permanent | отопитель заблокирован | снять блокировку, запустить снова, разобрать остальные ошибки | Altox |
| 88 | 136 | Fuel pump failure | обрыв насоса-дозатора или замыкание на плюс | электрика топливоподачи | Altox |
| 89 | 137 | Combustion air fan interruption | обрыв вентилятора камеры сгорания или замыкание на плюс | электрика вентилятора | Altox |
| 8A | 138 | Glow plug / flame monitor interruption | обрыв свечи накаливания или датчика пламени |  | Altox |
| 8B | 139 | Circulation pump interruption | обрыв циркуляционного насоса или замыкание на плюс | электрика контура ОЖ | Altox |
| 8D | 141 | Green LED interruption | обрыв зелёного светодиода |  | Altox |
| 8E | 142 | Yellow LED interruption | обрыв жёлтого светодиода |  | Altox |
| 90 | 144 | Solenoid valve interruption | обрыв клапана переключения ОЖ или замыкание на плюс | электрика клапана | Altox |
| 91 | 145 | Control unit locked or coded as neutral | блок управления заблокирован или закодирован как нейтральный |  | Altox |
| 92 | 146 | Command refresh failure | не обновляется команда по W-Bus | проводка W-Bus | Altox |
| 94 | 148 | Temperature sensor interruption | обрыв датчика температуры или замыкание на плюс | электрика датчиков температуры | Altox |
| 95 | 149 | Combustion air fan tight | вентилятор камеры сгорания туго вращается |  | Altox |
| 97 | 151 | Overheat sensor position wrong | неверное положение датчика перегрева |  | Altox |
| 98 | 152 | Power supply interruption | прерывание питания |  | Altox |
| 99 | 153 | Glow plug / electronic ignition unit interruption | обрыв свечи накаливания или электронного поджига | электрика свечи накаливания | Altox |
| 9A | 154 | Flame sensor interruption | обрыв датчика пламени |  | Altox |
| 9B | 155 | Setpoint transmitter invalid | неверный сигнал задатчика температуры |  | Altox |
| 9C | 156 | Intelligent undervoltage detection | превышено время нагрева: защита АКБ от разряда | не дольше ли нагрев, чем поездки; бортсеть | Altox |
| 9D | 157 | Solenoid valve shed test interruption | обрыв электромагнитного клапана при проверке |  | Altox |
| 9E | 158 | Fuel sensor interruption | обрыв датчика топлива |  | Altox |
| 9F | 159 | Nozzle stock heating interruption | обрыв подогрева топливной форсунки |  | Altox |
| A0 | 160 | Operating indicator interruption | обрыв индикатора работы |  | Altox |
| A1 | 161 | Flame indicator interruption | обрыв индикатора пламени |  | Altox |
| A5 | 165 | Fuel pre heating interruption | обрыв предпрогрева топлива |  | Altox |
| A6 | 166 | PCB temperature sensor interruption | обрыв датчика температуры блока управления |  | Altox |
| A8 | 168 | Communication board net energy manager error | ошибка связи с энергоменеджером автомобиля |  | Altox |
| A9 | 169 | Insufficient coolant flow | недостаточный поток ОЖ | контур ОЖ, циркуляционный насос, датчики температуры, течи | Evo |
| AA | 170 | Send on W-Bus not succeeded | нет ответа по W-Bus | проводка W-Bus | Altox |
| AB | 171 | Overheat sensor interruption | обрыв датчика перегрева или замыкание на плюс | электрика датчиков температуры | Altox |
| AC | 172 | The pressure sensor failure | неисправен датчик давления |  | Altox |
| B6 | 182 | Thermal fuse interrupted | обрыв термопредохранителя |  | Altox |
