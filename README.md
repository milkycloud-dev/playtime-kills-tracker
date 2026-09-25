<p align="center"><img src="assets/icon.png" width="128" height="128" alt="Playtime Kills Tracker icon"></p>

<h1 align="center">Playtime Kills Tracker</h1>

<p align="center">Bukkit plugin that records playtime and hostile mob kills in MySQL or MariaDB. Modded mobs are recognised automatically, mob farms are not counted.</p>

<p align="center">
  <a href="https://github.com/milkycloud-dev/playtime-kills-tracker/actions/workflows/build.yml"><img src="https://github.com/milkycloud-dev/playtime-kills-tracker/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="https://github.com/milkycloud-dev/playtime-kills-tracker/releases/latest"><img src="https://img.shields.io/github/v/release/milkycloud-dev/playtime-kills-tracker" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-proprietary-lightgrey" alt="License: proprietary"></a>
</p>

<p align="center"><a href="#english">English</a> | <a href="#русский">Русский</a></p>

<a id="english"></a>

## English

### Overview

The plugin keeps two numbers per player: time spent online and hostile mobs killed. Lifetime totals and per-day counters are written to a MySQL or MariaDB database, so a website, a Discord bot or a spreadsheet can build leaderboards with plain SQL and no plugin API.

It was written for a modded server on a NeoForge + Bukkit hybrid core, where most mobs come from mods and the Bukkit API often reports them as `UNKNOWN`. The same jar works on plain Paper or Spigot.

### What counts as a kill

- A mob is hostile when the server core puts it in the `MONSTER` category. Mods register their mobs through the same category as vanilla, so modded mobs count without per-mod lists. Bukkit interfaces (`Monster`, `Boss`, `Slime`) are the fallback.
- Animals, villagers, golems and tamed mobs never count, even if a mod marks them hostile.
- `never-monster` and `count-as-monster` in the config fix individual cases without a rebuild. By default, endermen, silverfish, endermites, slimes, magma cubes and zombified piglins are excluded because they are easy to farm.
- Farm protection looks at place and movement, not at the mob type:
  - a chunk counts at most 64 kills per hour;
  - kills do not count while the player has stayed within 3 blocks for 5 minutes.

### How data is stored

Counters build up in memory and go to the database in one asynchronous batch every 60 seconds. A crash loses at most one interval, and the database does not get a query per kill. Tables are created on first start:

| Table | Contents |
|---|---|
| `players` | UUID, last known name, total seconds, total kills, first and last seen |
| `daily` | the same counters per calendar day, for "player of the day" style tops |
| `kill_types` | every entity type that was killed, its core category and whether it counted |
| `kill_log` | optional per-kill journal with position and reason, pruned after `kill-log-days` |
| `meta` | service values |

`kill_types` is the quickest way to spot a modded mob that is counted wrongly.

### Requirements

- Java 21
- Paper, Spigot or a Bukkit hybrid core (Mohist, Arclight, Youer), API 1.13 or newer. In production on a NeoForge 1.21.1 hybrid core.
- MySQL 5.7+ or MariaDB 10.3+. The MariaDB JDBC driver is bundled in the jar.

### Installation

1. Download `playtime-kills-tracker-<version>.jar` from [Releases](https://github.com/milkycloud-dev/playtime-kills-tracker/releases) and put it into `plugins/`.
2. Start the server once. The plugin creates `plugins/NoteBunsStats/config.yml` and disables itself because the database is not set yet.
3. Fill in the `mysql` section and restart. The tables are created automatically.

The internal plugin name and the permission node are kept from the first deployment, so existing data folders keep working.

### Configuration

| Key | Default | Meaning |
|---|---|---|
| `mysql.host`, `port`, `database`, `username`, `password` | empty, `3306` | database connection |
| `flush-seconds` | `60` | how often buffered counters are written (minimum 15) |
| `kill-log-days` | `30` | how long `kill_log` rows are kept; `0` turns the journal off |
| `anti-farm.chunk-kills-per-hour` | `64` | kill cap per chunk per hour |
| `anti-farm.afk-minutes` | `5` | how long a player may stand still before kills stop counting |
| `anti-farm.afk-radius` | `3` | how far, in blocks, counts as "moved" |
| `never-monster` | 6 entries | entity ids that never count |
| `count-as-monster` | empty | entity ids that always count |

### Commands and permissions

| Command | Permission | Effect |
|---|---|---|
| `/playtime` (`/pt`) | none | your playtime and kills with your rank |
| `/playtime top` | none | top 5 by playtime and top 5 by kills |
| `/playtime flush` | `notebunsstats.admin` (op) | write buffered counters to the database now |

### Releases

Every tag `v*` is built by GitHub Actions from this source and published in [Releases](../../releases). The build log of each release is public, so every jar can be traced to its commit.

### Design notes

- Player positions are sampled every 10 seconds instead of listening to `PlayerMoveEvent`, which fires on every step of every player.
- Bukkit objects are read on the main thread only; the database write runs in an async task with a snapshot of the numbers.
- Log rows use typed JDBC setters. With `setObject` the MariaDB driver looks up codecs through the context class loader, which in a Bukkit async task belongs to the server, and every write failed.

### Known limitations

- Player messages are in Russian and are not configurable yet.
- The day boundary of the `daily` table is fixed to the `Europe/Moscow` time zone.
- MySQL or MariaDB is required. There is no file storage fallback, and the plugin disables itself if the database is unreachable at startup.
- Playtime counts every online second. AFK time is only excluded from kills.

### License

Proprietary, all rights reserved. Official release binaries may be run unmodified on servers you operate. Copying, modifying or redistributing the code or the binaries requires written permission. Full terms: [LICENSE](LICENSE).

The jar bundles MariaDB Connector/J, which stays under LGPL-2.1.

<a id="русский"></a>

## Русский

### Обзор

Плагин ведёт по каждому игроку два числа: время в игре и убитых враждебных мобов. Общие итоги и счётчики за каждый день пишутся в MySQL или MariaDB, поэтому топы можно строить обычным SQL: на сайте, в Discord-боте или в таблице, без API плагина.

Писался для модового сервера на гибридном ядре NeoForge + Bukkit. Там большинство мобов приходит из модов, а Bukkit часто видит их как `UNKNOWN`. На обычном Paper или Spigot тот же jar тоже работает.

### Что считается убийством

- Моб враждебный, если ядро относит его к категории `MONSTER`. Моды регистрируют мобов через ту же категорию, что и ваниль, поэтому модовые мобы засчитываются без списков под каждый мод. Запасной вариант: интерфейсы Bukkit (`Monster`, `Boss`, `Slime`).
- Животные, жители, големы и приручённые мобы не засчитываются никогда, даже если мод объявил их враждебными.
- Списки `never-monster` и `count-as-monster` в конфиге правят отдельные случаи без пересборки. По умолчанию исключены эндермены, чешуйницы, эндермиты, слаймы, магмовые кубы и зомбифицированные пиглины: их легко фармить.
- Защита от ферм смотрит на место и движение, а не на вид моба:
  - один чанк засчитывает не больше 64 убийств в час;
  - убийства не идут в счёт, пока игрок 5 минут не отходил дальше 3 блоков.

### Как хранятся данные

Счётчики копятся в памяти и раз в 60 секунд уходят в базу одной асинхронной пачкой. Падение сервера стоит не больше одного интервала, а база не получает запрос на каждое убийство. Таблицы создаются при первом запуске:

| Таблица | Что внутри |
|---|---|
| `players` | UUID, последний ник, всего секунд, всего убийств, первый и последний вход |
| `daily` | те же счётчики по дням, для топов вроде «игрок дня» |
| `kill_types` | каждый убитый тип сущности, его категория в ядре и засчитан ли он |
| `kill_log` | необязательный журнал убийств с координатами и причиной, чистится через `kill-log-days` |
| `meta` | служебные значения |

По `kill_types` быстрее всего видно, какой модовый моб считается неправильно.

### Требования

- Java 21
- Paper, Spigot или гибридное ядро Bukkit (Mohist, Arclight, Youer), API 1.13 и новее. В работе на гибридном ядре NeoForge 1.21.1.
- MySQL 5.7+ или MariaDB 10.3+. Драйвер MariaDB JDBC вшит в jar.

### Установка

1. Скачать `playtime-kills-tracker-<версия>.jar` из [Releases](https://github.com/milkycloud-dev/playtime-kills-tracker/releases) и положить в `plugins/`.
2. Запустить сервер один раз. Плагин создаст `plugins/NoteBunsStats/config.yml` и выключится, потому что база ещё не указана.
3. Заполнить раздел `mysql` и перезапустить сервер. Таблицы создадутся сами.

Внутреннее имя плагина и право доступа остались от первой установки, чтобы старые папки с данными продолжали работать.

### Настройки

| Ключ | По умолчанию | Назначение |
|---|---|---|
| `mysql.host`, `port`, `database`, `username`, `password` | пусто, `3306` | подключение к базе |
| `flush-seconds` | `60` | как часто накопленное пишется в базу (не меньше 15) |
| `kill-log-days` | `30` | сколько дней хранить `kill_log`; `0` выключает журнал |
| `anti-farm.chunk-kills-per-hour` | `64` | потолок убийств на чанк в час |
| `anti-farm.afk-minutes` | `5` | сколько можно стоять на месте, пока убийства ещё засчитываются |
| `anti-farm.afk-radius` | `3` | на сколько блоков надо отойти, чтобы это считалось движением |
| `never-monster` | 6 записей | сущности, которые не засчитываются никогда |
| `count-as-monster` | пусто | сущности, которые засчитываются всегда |

### Команды и права

| Команда | Право | Что делает |
|---|---|---|
| `/playtime` (`/pt`, `/время`, `/стата`) | не нужно | своё время и убийства с местом в топе |
| `/playtime top` | не нужно | топ-5 по времени и топ-5 по убийствам |
| `/playtime flush` | `notebunsstats.admin` (op) | сразу записать накопленное в базу |

### Релизы

Каждый тег `v*` собирается из этих исходников в GitHub Actions и публикуется в [Releases](../../releases). Лог сборки каждого релиза открыт, так что любой jar можно сверить с его коммитом.

### Устройство

- Положение игроков опрашивается раз в 10 секунд. `PlayerMoveEvent` срабатывает на каждый шаг каждого игрока, и слушать его дорого.
- Объекты Bukkit читаются только на главном потоке. В базу пишет асинхронная задача, которой передаётся снимок чисел.
- Строки журнала пишутся типизированными сеттерами JDBC. С `setObject` драйвер MariaDB ищет кодеки через контекстный загрузчик классов, а в асинхронной задаче Bukkit он серверный, и запись не проходила.

### Известные ограничения

- Сообщения игрокам на русском и пока не настраиваются.
- Граница суток для таблицы `daily` жёстко задана по часовому поясу `Europe/Moscow`.
- Нужна MySQL или MariaDB. Хранения в файлах нет, и если база недоступна при старте, плагин выключается.
- Время в игре считается целиком, AFK исключается только из убийств.

### Лицензия

Проприетарная, все права защищены. Официальные сборки из релизов можно запускать без изменений на своих серверах. Копировать, изменять и распространять код или сборки можно только с письменного разрешения. Полный текст: [LICENSE](LICENSE).

В jar вшит MariaDB Connector/J, он остаётся под LGPL-2.1.
