# HTTP接口清单与测试证据索引

生成日期：2026-10-30。按 Controller 的 HTTP 方法与完整路径计数，共 12 个控制器、54 项映射；同一路径的不同方法分别计数。不包含 Actuator、静态资源或框架错误页面。

下列“关联测试类”用于查找业务域证据，不代表对每项接口、每个分支或真实外部服务的完整覆盖。逐项人工请求覆盖率未测量，不能用 54 作为已执行用例数量。微信相关服务测试使用受控替身，真实微信授权仍待人工验收。

测试类均位于 `backend/src/test/java/com/learningmaterial/system/`；实际执行结果见同目录的《功能测试修复记录》。

| 编号 | HTTP方法与路径 | Controller锚点 | 关联测试类 |
|---|---|---|---|
| 01 | `GET /api/v1/admin/ai-tasks` | `AdminTaskController.java:24` | AdminTaskIntegrationTest |
| 02 | `GET /api/v1/admin/ai-tasks/{id}/steps` | `AdminTaskController.java:34` | AdminTaskIntegrationTest |
| 03 | `GET /api/v1/admin/users` | `AdminUserController.java:30` | AdminUserIntegrationTest |
| 04 | `PATCH /api/v1/admin/users/{id}/status` | `AdminUserController.java:39` | AdminUserIntegrationTest |
| 05 | `PUT /api/v1/ai-tasks/{id}/confirmation` | `AiTaskController.java:44` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 06 | `POST /api/v1/ai-tasks` | `AiTaskController.java:51` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 07 | `GET /api/v1/ai-tasks` | `AiTaskController.java:59` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 08 | `GET /api/v1/ai-tasks/{id}` | `AiTaskController.java:68` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 09 | `GET /api/v1/ai-tasks/{id}/result` | `AiTaskController.java:74` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 10 | `GET /api/v1/ai-tasks/{id}/sources` | `AiTaskController.java:81` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 11 | `GET /api/v1/ai-tasks/{id}/steps` | `AiTaskController.java:88` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 12 | `POST /api/v1/ai-tasks/{id}/cancellations` | `AiTaskController.java:97` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 13 | `POST /api/v1/ai-tasks/{id}/retries` | `AiTaskController.java:103` | AiTaskFlowIntegrationTest、ConfirmationIntegrationTest、AiTaskWorkerIntegrationTest |
| 14 | `POST /api/v1/auth/registrations` | `AuthController.java:33` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 15 | `POST /api/v1/auth/sessions` | `AuthController.java:39` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 16 | `DELETE /api/v1/auth/sessions/current` | `AuthController.java:45` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 17 | `GET /api/v1/users/me` | `AuthController.java:51` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 18 | `PATCH /api/v1/users/me` | `AuthController.java:56` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 19 | `POST /api/v1/auth/wechat-sessions` | `AuthController.java:62` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 20 | `POST /api/v1/users/me/wechat-binding` | `AuthController.java:68` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 21 | `DELETE /api/v1/users/me/wechat-binding` | `AuthController.java:75` | AuthFlowIntegrationTest、AccountFeaturesServiceTest（微信客户端为受控替身） |
| 22 | `PUT /api/v1/cards/{id}/parent` | `CardController.java:24` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 23 | `GET /api/v1/cards` | `CardController.java:30` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 24 | `POST /api/v1/cards` | `CardController.java:39` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 25 | `PATCH /api/v1/cards/{id}` | `CardController.java:46` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 26 | `GET /api/v1/cards/{id}` | `CardController.java:52` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 27 | `DELETE /api/v1/cards/{id}` | `CardController.java:58` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 28 | `GET /api/v1/cards/{id}/sources` | `CardController.java:65` | CardManagementIntegrationTest、CardDeletionServiceTest、CardOrganizationIntegrationTest、ApiExceptionHandlerTest |
| 29 | `GET /api/v1/card-layouts/{layoutKey}` | `CardLayoutController.java:17` | CardOrganizationIntegrationTest |
| 30 | `PUT /api/v1/card-layouts/{layoutKey}` | `CardLayoutController.java:22` | CardOrganizationIntegrationTest |
| 31 | `POST /api/v1/card-relations` | `CardRelationController.java:22` | CardRelationIntegrationTest |
| 32 | `GET /api/v1/card-relations` | `CardRelationController.java:29` | CardRelationIntegrationTest |
| 33 | `DELETE /api/v1/card-relations/{id}` | `CardRelationController.java:36` | CardRelationIntegrationTest |
| 34 | `GET /api/v1/knowledge-graphs` | `CardRelationController.java:40` | CardRelationIntegrationTest |
| 35 | `POST /api/v1/materials/{id}/review-requests` | `ContentReviewController.java:30` | ContentReviewIntegrationTest |
| 36 | `GET /api/v1/materials/{id}/review-requests` | `ContentReviewController.java:35` | ContentReviewIntegrationTest |
| 37 | `GET /api/v1/admin/reviews` | `ContentReviewController.java:39` | ContentReviewIntegrationTest |
| 38 | `POST /api/v1/admin/reviews/{id}/content-accesses` | `ContentReviewController.java:43` | ContentReviewIntegrationTest |
| 39 | `PUT /api/v1/admin/reviews/{id}/decision` | `ContentReviewController.java:48` | ContentReviewIntegrationTest |
| 40 | `GET /api/v1/admin/reviews/{id}/file` | `ContentReviewController.java:55` | ContentReviewIntegrationTest |
| 41 | `PUT /api/v1/favorites/{type}/{id}` | `FavoriteController.java:26` | FavoriteFlowIntegrationTest |
| 42 | `DELETE /api/v1/favorites/{type}/{id}` | `FavoriteController.java:33` | FavoriteFlowIntegrationTest |
| 43 | `GET /api/v1/favorites` | `FavoriteController.java:40` | FavoriteFlowIntegrationTest |
| 44 | `POST /api/v1/materials` | `MaterialController.java:40` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 45 | `POST /api/v1/materials/files` | `MaterialController.java:48` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 46 | `GET /api/v1/materials` | `MaterialController.java:60` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 47 | `GET /api/v1/materials/{id}` | `MaterialController.java:73` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 48 | `GET /api/v1/materials/{id}/file` | `MaterialController.java:79` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 49 | `PATCH /api/v1/materials/{id}` | `MaterialController.java:93` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 50 | `DELETE /api/v1/materials/{id}` | `MaterialController.java:100` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 51 | `GET /api/v1/tags` | `MaterialController.java:107` | MaterialFlowIntegrationTest、ImageFlowIntegrationTest、PdfFlowIntegrationTest、PersonalMaterialSearchIntegrationTest |
| 52 | `GET /api/v1/notifications` | `NotificationController.java:19` | NotificationIntegrationTest |
| 53 | `PUT /api/v1/notifications/{id}/read-state` | `NotificationController.java:26` | NotificationIntegrationTest |
| 54 | `GET /api/v1/admin/statistics` | `StatisticsController.java:18` | StatisticsIntegrationTest |
