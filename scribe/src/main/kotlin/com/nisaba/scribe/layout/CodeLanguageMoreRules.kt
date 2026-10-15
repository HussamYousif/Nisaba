package com.nisaba.scribe.layout

// The second half of the language table (CodeLanguageRules).

internal val LANGS_B: Map<String, Lang> = buildMap {
    fun add(names: String, lang: Lang) { names.split(' ').forEach { put(it, lang) } }
    add(
        "rb ruby",
        Lang(
            words(
                "def end class module if elsif else unless while until for in do return yield begin rescue ensure raise then " +
                    "case when nil true false self super require attr_accessor and or not puts lambda proc",
            ),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, identExtra = "?!",
            keywordPrefix = ':',
        ),
    )
    add(
        "php",
        Lang(
            words(
                "function class public private protected static return if else elseif for foreach while do switch case default " +
                    "break continue new echo print namespace use try catch finally throw null true false array as fn match",
            ),
            lineComments = listOf("//", "#"), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "lua",
        Lang(
            words("function local end if then elseif else for in do while repeat until return break nil true false and or not"),
            lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "r",
        Lang(
            words("function if else for while repeat in next break return TRUE FALSE NULL NA library"),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, identExtra = ".",
        ),
    )
    add(
        "ex exs elixir",
        Lang(
            words(
                "def defp defmodule defstruct do end fn if else unless case cond when with for in import alias use require " +
                    "nil true false and or not raise try rescue",
            ),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false, identExtra = "?!",
            keywordPrefix = ':',
        ),
    )
    add(
        "erl erlang",
        Lang(
            words("module export import fun case of if when end receive after try catch begin let true false"),
            lineComments = listOf("%"), blockComments = emptyList(), charQuote = false,
        ),
    )
    add(
        "pl perl",
        Lang(
            words("my our sub if elsif else unless while until for foreach return use package print last next undef"),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "jl julia",
        Lang(
            words("function end if elseif else for while in return module using import struct mutable let begin do true false nothing"),
            lineComments = listOf("#"), blockComments = listOf("#=" to "=#"),
        ),
    )
    add(
        "matlab octave",
        Lang(
            words("function end if elseif else for while switch case otherwise return break continue true false"),
            lineComments = listOf("%"), blockComments = listOf("%{" to "%}"), quotes = "\"'", charQuote = false,
        ),
    )
    add(
        "hs haskell",
        Lang(
            words(
                "module where import qualified as hiding data type newtype class instance deriving let in if then else case of " +
                    "do mdo forall infix infixl infixr True False Nothing Just Left Right return",
            ),
            lineComments = listOf("--"), blockComments = listOf("{-" to "-}"), identExtra = "'",
        ),
    )
    add(
        "clj cljs cljc edn clojure",
        Lang(
            words(
                "def defn defn- defmacro defrecord defprotocol deftype ns let letfn fn if if-not when when-not cond condp case " +
                    "do loop recur doseq dotimes for try catch finally throw quote require import use nil true false and or not",
            ),
            lineComments = listOf(";"), blockComments = emptyList(), charQuote = false, identExtra = "-?!*+<>=/.", keywordPrefix = ':',
        ),
    )
    add(
        "lisp scheme scm racket rkt elisp el",
        Lang(
            words(
                "define defun defvar defparameter defmacro lambda let let* letrec if cond case when unless begin progn loop do " +
                    "and or not quote setq setf nil t else",
            ),
            lineComments = listOf(";"), blockComments = listOf("#|" to "|#"), charQuote = false, identExtra = "-?!*+<>=/",
            keywordPrefix = ':',
        ),
    )
    add(
        "sql mysql sqlite tsql plsql mariadb",
        Lang(
            words(
                "select from where insert into values update set delete create table drop alter join left right inner outer on " +
                    "group by order having limit as and or not null is in like distinct count sum avg min max primary key foreign " +
                    "references index view union all case when then else end with returning",
            ),
            lineComments = listOf("--"), quotes = "'\"", charQuote = false, ignoreCase = true,
        ),
    )
    add(
        // PostgreSQL and PL/pgSQL: double quotes are identifiers ("User"), not strings; `$$` bodies are code, so they
        // are highlighted as such rather than as one long string.
        "postgres postgresql psql pgsql plpgsql pg",
        Lang(
            words(
                "select from where insert into values update set delete create table drop alter join left right inner outer full " +
                    "cross natural lateral using on group by order having limit offset fetch as and or not null is in like ilike " +
                    "similar distinct exists between case when then else end with recursive returning union intersect except all " +
                    "any some over partition window filter within primary key foreign references unique check default constraint " +
                    "index view materialized schema sequence extension trigger function procedure returns return language " +
                    "declare begin commit rollback do loop while for foreach exit continue if elsif raise notice exception " +
                    "perform execute grant revoke cascade restrict truncate vacuum analyze explain copy conflict nothing " +
                    "integer int bigint smallint serial bigserial numeric decimal real double precision boolean bool text " +
                    "varchar char uuid json jsonb date time timestamp timestamptz interval bytea array record void setof " +
                    "true false coalesce nullif greatest least now count sum avg min max cast",
            ),
            lineComments = listOf("--"), quotes = "'", charQuote = false, ignoreCase = true,
        ),
    )
    add(
        "sh bash shell zsh console fish ps powershell ps1",
        Lang(
            words("if then else elif fi for while do done case esac in function return echo export local cd"),
            lineComments = listOf("#"), blockComments = emptyList(), quotes = "\"'", charQuote = false,
        ),
    )
}
