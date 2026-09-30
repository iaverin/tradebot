# Фоновое обновление prediction markets и similar markets

## Статус

- [x] План согласован.
- [x] Изменения реализованы.
- [x] Автоматические тесты проходят.
- [ ] Изменения проверены в полном Docker-окружении.

## Журнал выполнения

- [x] 2026-09-02 — добавлены Flyway- и Alembic-миграции staging-таблиц.
- [x] 2026-09-02 — `similar-markets` worker изолирован от production-таблиц.
- [x] 2026-09-02 — добавлена атомарная публикация двух production-таблиц.
- [x] 2026-09-02 — перестроен порядок `MarketsRefreshWorkflow` и lifecycle монитора.
- [x] 2026-09-02 — удалён ручной partial-publish и унифицирован запуск полного refresh.
- [x] 2026-09-02 — добавлены worker, workflow и transaction rollback тесты.
- [x] 2026-09-02 — итоговые проверки: backend 96/96, similar-markets 7/7, frontend build, Ruff и миграции успешны.
- [x] 2026-09-02 — тесты `similar-markets` переведены с raw SQL и прямой работы с session на методы репозиториев.
- [x] 2026-09-02 — для staging-таблицы сохранено исходное имя `fetching_prediction_markets`.
- [x] 2026-09-02 — имя staging-таблицы похожих рынков изменено на `similar_markets_staging` во всех компонентах.

## Цель

Разделить подготовку и публикацию данных так, чтобы `similar-markets` worker не работал с production-таблицами, а арбитражный монитор продолжал использовать старый согласованный snapshot во время загрузки и расчёта нового.

Целевой поток:

```text
очистить fetching_prediction_markets
        ↓
параллельно загрузить рынки с площадок
        ↓
запустить similar-markets worker
        ↓
worker читает fetching_prediction_markets
и записывает similar_markets_staging
        ↓
остановить арбитражный монитор
        ↓
атомарно заменить prediction_markets и similar_markets
        ↓
запустить арбитражный монитор
```

## Основные инварианты

- [x] `similar-markets` worker читает рынки только из `fetching_prediction_markets`.
- [x] `similar-markets` worker записывает пары только в `similar_markets_staging`.
- [x] `similar-markets` worker не читает и не изменяет `prediction_markets` и `similar_markets`.
- [x] Во время загрузки и расчёта production-таблицы остаются без изменений.
- [x] `prediction_markets` и `similar_markets` публикуются в одной транзакции.
- [x] Арбитражный монитор останавливается только на время публикации готового snapshot.
- [x] При ошибке до публикации монитор продолжает работать со старым snapshot.
- [x] При ошибке публикации транзакция откатывается, после чего монитор запускается на старом snapshot.

## 1. Схема базы данных

### 1.1. Backend/Flyway

- [x] Добавить новую Flyway-миграцию в `backend/src/main/resources/db/migration`, не изменяя уже применённые миграции.
- [x] Сохранить исходное имя staging-таблицы `fetching_prediction_markets`.
- [x] Сохранить существующие имена связанных индексов и sequence.
- [x] Создать `similar_markets_staging` со структурой, совместимой с `similar_markets`.
- [x] Добавить в `similar_markets_staging` колонку `enabled BOOLEAN NOT NULL DEFAULT TRUE`.
- [x] Проверить типы, nullable-ограничения и значения по умолчанию для всех колонок staging- и production-таблиц.
- [x] Сделать миграцию безопасной при наличии таблиц, созданных вторым механизмом миграций.

### 1.2. Similar Markets/Alembic

- [x] Добавить следующую Alembic revision в `similar-markets/migrations/versions`.
- [x] Использовать существующую staging-таблицу `fetching_prediction_markets` без переименования.
- [x] Создать `similar_markets_staging` с той же согласованной схемой.
- [x] Сделать операции идемпотентными, поскольку Flyway и Alembic сейчас применяются к общей базе данных.
- [x] Добавить корректный downgrade либо явно задокументировать ограничения отката после публикации данных.

### 1.3. Владение общей схемой

- [x] Зафиксировать backend/Flyway как основного владельца общих production-таблиц; Alembic поддерживает worker-схему и локальные тесты.
- [x] До отдельного упрощения миграционной архитектуры поддерживать эквивалентные Flyway- и Alembic-изменения без расхождения схем.

## 2. Изоляция similar-markets worker

### 2.1. ORM-модели

Файл: `similar-markets/app/models.py`.

- [x] Сохранить `PredictionMarket` как модель staging-таблицы.
- [x] Установить `__tablename__ = "fetching_prediction_markets"`.
- [x] Заменить `SimilarMarket` на модель staging-таблицы `SimilarMarketStaging`.
- [x] Установить `__tablename__ = "similar_markets_staging"`.
- [x] Удалить из worker ORM-маппинги production-таблиц `prediction_markets` и `similar_markets`.
- [x] Сохранить `SimilarEvent` и таблицу `similar_events` как внутренний промежуточный результат расчёта.

### 2.2. Репозитории

Файл: `similar-markets/app/repositories.py`.

- [x] Сохранить `PredictionMarketsRepository` для staging-таблицы.
- [x] Перевести `get_distinct_events()` на `fetching_prediction_markets`.
- [x] Перевести `get_markets_for_event_pair()` на `fetching_prediction_markets`.
- [x] Переименовать `SimilarMarketsRepository` в staging-репозиторий.
- [x] Перевести очистку на `TRUNCATE TABLE similar_markets_staging`.
- [x] Перевести сохранение найденных пар на `similar_markets_staging`.
- [x] Убедиться, что в модуле отсутствуют SQL-запросы к `prediction_markets` и `similar_markets`.

### 2.3. Сервисы и DI

Файлы:

- `similar-markets/app/services.py`;
- `similar-markets/app/ioc.py`;
- `similar-markets/app/schemas.py`.

- [x] Обновить типы и зависимости сервисов на staging-модели и репозитории.
- [x] В начале расчёта похожих рынков очищать `similar_markets_staging`.
- [x] Сохранить текущую последовательность расчёта `similar_events`, затем `similar_markets_staging`.
- [x] Проверить, что неуспешный расчёт приводит к ошибке child workflow и не разрешает backend публиковать staging-данные.
- [x] Обновить логирование: в сообщениях явно различать staging и production.

### 2.4. Python API

Файл: `similar-markets/app/api/similar_markets.py`.

- [x] Удалить вызов backend endpoint `/api/arbitrage/restart`.
- [x] Удалить `restart_arbitrage_monitor()` и больше не управлять монитором из Python-сервиса.
- [x] Убедиться, что `httpx` отсутствует в runtime dependencies (он остаётся только в dev-группе для API-тестов).
- [x] Зафиксировать в именах/описании API, что прямой запуск создаёт только staging-данные и не публикует production snapshot.

## 3. Backend: staging и атомарная публикация

### 3.1. Entity и repository

Файлы:

- `backend/src/main/java/hzpro/com/tradingdesk/entity/PredictionMarket.java`;
- `backend/src/main/java/hzpro/com/tradingdesk/repository/PredictionMarketRepository.java`.

- [x] Сохранить `@Table(name = "fetching_prediction_markets")` у `PredictionMarket`.
- [x] Использовать исходное имя staging-таблицы в SQL очистки.
- [x] Добавить очистку production-таблиц `prediction_markets` и `similar_markets`.
- [x] Добавить копирование `fetching_prediction_markets` в `prediction_markets`.
- [x] Добавить копирование `similar_markets_staging` в `similar_markets`.
- [x] Во всех `INSERT ... SELECT` перечислить колонки явно; не использовать `SELECT *`.
- [x] Проверить перенос `yes_token_id`, `no_token_id`, `condition_id`, timestamps и `enabled`.

### 3.2. Правила идентификаторов

- [x] Не копировать ID из `similar_markets_staging` в `similar_markets`.
- [x] Не выполнять `RESTART IDENTITY` для `similar_markets` при каждой публикации.
- [x] Генерировать новые монотонно возрастающие production-ID, чтобы старые `arbitrage_events.similar_market_id` не начали указывать на другие пары.
- [x] После копирования проверить состояние sequence для `prediction_markets` и `similar_markets`.

### 3.3. Транзакционная публикация

- [x] Создать один `@Transactional` метод публикации полного snapshot.
- [x] Внутри одной транзакции очистить `prediction_markets` и `similar_markets`.
- [x] В той же транзакции скопировать обе staging-таблицы в production.
- [x] Убедиться, что при ошибке любого SQL-шага откатываются обе production-таблицы.
- [x] Учесть, что `TRUNCATE` берёт блокировки: читатели должны увидеть либо старый, либо новый snapshot, но не промежуточно пустые таблицы.
- [x] Сделать публикацию идемпотентной на случай повтора Temporal activity после потерянного подтверждения.

## 4. Backend: Temporal workflow

### 4.1. Activity API

Файлы:

- `backend/src/main/java/hzpro/com/tradingdesk/marketsfetcher/temporal/activity/FetchActivity.java`;
- `backend/src/main/java/hzpro/com/tradingdesk/marketsfetcher/temporal/activity/FetchActivityImpl.java`.

- [x] Оставить activity очистки `fetching_prediction_markets`.
- [x] Сохранить отдельные fetch activity для Kalshi, Polymarket и Opinion.
- [x] Заменить `publishFetchedMarkets()` на публикацию полного snapshot, например `publishMarketsSnapshot()`.
- [x] Заменить `pauseArbitrageMonitor()`/`resumeArbitrageMonitor()` на однозначные `stopArbitrageMonitor()`/`startArbitrageMonitor()`.
- [x] Не подавлять ошибки остановки и запуска монитора.
- [x] Сделать stop/start activity идемпотентными.

### 4.2. Порядок workflow

Файл: `backend/src/main/java/hzpro/com/tradingdesk/marketsfetcher/temporal/workflow/MarketsRefreshWorkflowImpl.java`.

- [x] Убрать остановку монитора из начала workflow.
- [x] Первым шагом очистить `fetching_prediction_markets`.
- [x] Параллельно запустить загрузку Kalshi, Polymarket и Opinion.
- [x] Дождаться успешного завершения всех fetch activity.
- [x] Запустить и дождаться `FindSimilarMarketWorkflow` на task queue `similar-markets`.
- [x] Только после успешного расчёта staging-данных остановить арбитражный монитор.
- [x] Опубликовать обе production-таблицы одной activity/транзакцией.
- [x] Запустить арбитражный монитор после успешной публикации.

### 4.3. Обработка ошибок

- [x] При ошибке загрузки не останавливать монитор и не изменять production.
- [x] При ошибке child workflow не останавливать монитор и не изменять production.
- [x] Ограничить `finally` блоком, который начинается с запроса остановки монитора.
- [x] Если публикация упала после остановки монитора, запустить монитор в `finally` на старом snapshot.
- [x] Ошибку финального запуска монитора не скрывать: workflow должен завершаться ошибкой и применять retry policy.
- [x] Проверить сценарий, когда монитор уже остановлен до начала публикации.
- [x] Исключить конкурентный запуск refresh workflow единым workflow ID и schedule overlap policy `SKIP`.

## 5. Ручные endpoints и альтернативные пути публикации

Файлы:

- `backend/src/main/java/hzpro/com/tradingdesk/similarmarkets/service/SimilarMarketsService.java`;
- `backend/src/main/java/hzpro/com/tradingdesk/controller/SimilarMarketsController.java`;
- `backend/src/main/java/hzpro/com/tradingdesk/controller/MarketsFetcherController.java`.

- [x] Перевести `/similar-markets/recalculate-similar-markets` с прямого child workflow на полный `MarketsRefreshWorkflow`.
- [x] Вынести создание и запуск `MarketsRefreshWorkflow` в один backend-сервис, используемый обоими контроллерами.
- [x] Обновить сообщения endpoint, чтобы они отражали полный refresh.
- [x] Удалить `/persist-fetching-data`, поскольку он публиковал только `prediction_markets` и нарушал согласованность snapshot.
- [x] Проверить остальные ручные endpoints: других путей частичной публикации production-таблиц нет.

## 6. Тесты similar-markets

Файлы:

- `similar-markets/tests/test_similar_markets.py`;
- `similar-markets/tests/conftest.py`;
- `similar-markets/tests/factories.py`.

- [x] Обновить factories и fixtures на `PredictionMarket` и `SimilarMarketStaging`.
- [x] Проверить чтение рынков из `fetching_prediction_markets`.
- [x] Проверить сохранение найденных пар в `similar_markets_staging`.
- [x] Перед запуском создать sentinel-записи в `prediction_markets` и `similar_markets` и проверить, что worker их не изменил.
- [x] Проверить очистку старых staging-данных при повторном запуске.
- [x] Проверить сценарий отсутствия похожих событий.
- [x] Проверить сценарий отсутствия похожих рынков.
- [x] Проверить, что ошибка child workflow не вызывает публикацию или управление монитором со стороны Python worker.

## 7. Тесты backend

- [x] Добавить unit/workflow-тест для точного порядка activity и child workflow.
- [x] Проверить параллельную загрузку площадок и ожидание завершения всех fetch activity.
- [x] Проверить, что child workflow начинается только после загрузки.
- [x] Проверить, что монитор останавливается только после успешного child workflow.
- [x] Проверить, что публикация выполняется между stop и start монитора.
- [x] Проверить восстановление монитора после ошибки публикации.
- [x] Добавить интеграционный тест атомарной публикации обеих таблиц через PostgreSQL/Testcontainers.
- [x] Проверить rollback обеих production-таблиц при ошибке второго копирования.
- [x] Проверить явное сопоставление всех колонок staging и production.
- [x] Проверить отсутствие повторного использования historical `similar_market_id`.
- [x] Проверить защиту от конкурентных refresh workflow.

## 8. Проверка и приёмка

### Similar Markets

- [x] Выполнить миграции, включая проверку upgrade/downgrade/upgrade на чистой временной PostgreSQL БД:

  ```bash
  cd similar-markets
  uv run alembic upgrade head
  ```

- [x] Запустить тесты — 7 тестов успешно:

  ```bash
  uv run pytest
  ```

- [x] Запустить lint — без ошибок:

  ```bash
  uv run ruff check .
  ```

### Backend

- [x] Запустить тесты — 96 тестов успешно:

  ```bash
  cd backend
  ./gradlew test
  ```

- [x] Проверить компиляцию — успешно:

  ```bash
  ./gradlew compileJava
  ```

### Полный сценарий

- [ ] Запустить PostgreSQL, Temporal, backend и similar-markets worker.
- [ ] Заполнить production-таблицы исходным snapshot.
- [ ] Запустить `MarketsRefreshWorkflow` вручную.
- [ ] Во время загрузки убедиться, что монитор работает со старым snapshot.
- [ ] Убедиться, что worker пишет только в staging-таблицу.
- [ ] Убедиться, что монитор останавливается непосредственно перед публикацией.
- [ ] Убедиться, что обе production-таблицы переключились согласованно.
- [ ] Убедиться, что монитор запустился с новым набором пар.
- [ ] Смоделировать ошибку fetch и проверить сохранность production и работу монитора.
- [ ] Смоделировать ошибку публикации и проверить rollback и восстановление монитора.

## Принятое предположение

Для staging-рынков сохраняется исходное имя таблицы `fetching_prediction_markets`.

## Примечание по развёртыванию

Перед развёртыванием изменённого порядка activity необходимо дождаться завершения активных `MarketsRefreshWorkflow` либо временно приостановить расписание. После запуска новый backend обновит существующий Temporal schedule, сохранив его paused/unpaused state.
