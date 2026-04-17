package com.fossylabs.portaserver.sql

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class QueryRequest(val sql: String)

@Serializable
private data class CreateDbRequest(val name: String)

@Serializable
private data class DbListResponse(val databases: List<String>)

@Serializable
private data class TableListResponse(val tables: List<String>)

@Serializable
private data class SqlQueryResponse(
    val columns: List<String>? = null,
    val rows: List<Map<String, String?>>? = null,
    val rowsAffected: Int? = null,
    val error: String? = null,
)

fun Route.sqlRoutes() {

    get("/api/db") {
        if (SqliteManager.dbDir == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Database directory not configured")
            return@get
        }
        call.respond(DbListResponse(databases = SqliteManager.listDatabases()))
    }

    post("/api/db") {
        val body = call.receive<CreateDbRequest>()
        val name = body.name.trim().filter { it.isLetterOrDigit() || it == '_' }
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Invalid database name")
            return@post
        }
        SqliteManager.openOrCreate(name)
        call.respond(HttpStatusCode.Created, DbListResponse(databases = SqliteManager.listDatabases()))
    }

    get("/api/db/{name}/tables") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.respond(TableListResponse(tables = SqliteManager.listTables(name)))
    }

    post("/api/db/{name}/query") {
        val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = call.receive<QueryRequest>()
        val result = SqliteManager.executeQuery(name, body.sql)
        val status = if (result.error != null) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK
        call.respond(
            status,
            SqlQueryResponse(
                columns = result.columns,
                rows = result.rows,
                rowsAffected = result.rowsAffected,
                error = result.error,
            ),
        )
    }
}

