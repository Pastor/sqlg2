package sqlg2;

import sqlg2.db.QueryReplacer;
import sqlg2.queries.QueryParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class QPParser {

    private static final class Pair {

        final String piece;
        final String ident;

        private Pair(String piece, String ident) {
            this.piece = piece;
            this.ident = ident;
        }
    }

    private final String location;
    private final boolean allowOutParams;
    private final String pred;
    private final boolean onlySql;
    private final List<String> parameters;
    private final Map<String, List<ParamCutPaste>> bindMap;

    QPParser(String location, boolean allowOutParams, String pred, boolean onlySql,
             List<String> parameters, Map<String, List<ParamCutPaste>> bindMap) {
        this.location = location;
        this.allowOutParams = allowOutParams;
        this.pred = pred;
        this.onlySql = onlySql;
        this.parameters = parameters;
        this.bindMap = bindMap;
    }

    BindVarCutPaste getStatementCutPaste(int from, int to, String sql) throws ParseException {
        final List<Pair> pairs = new ArrayList<Pair>();
        String rest = QueryReplacer.replace(sql, null, new QueryReplacer.Appender() {
            public void append(String buf, String ident) {
                pairs.add(new Pair(buf, ident));
            }
        }, false);
        return new QPBuilder().getStatementCutPaste(from, to, pairs, rest);
    }

    private static final class ParamInfo {

        final int position;
        final String id;
        final String expression;
        final String programString;
        final boolean out;

        private ParamInfo(int position, String id, String expression, String programString, boolean out) {
            this.position = position;
            this.id = id;
            this.expression = expression;
            this.programString = programString;
            this.out = out;
        }
    }

    private final class QPBuilder {

        private boolean first = true;
        private final StringBuilder total = new StringBuilder();
        private final List<ParamCutPaste> pieces = new ArrayList<ParamCutPaste>();

        private int append1(String what, boolean single) {
            int whatPos;
            if (single) {
                whatPos = total.length();
                total.append(what);
            } else {
                if (onlySql) {
                    if (!first) {
                        total.append(" + ");
                    }
                    whatPos = total.length();
                    total.append(what);
                } else {
                    if (first) {
                        total.append("new sqlg2.db.QueryBuilder(");
                        whatPos = total.length();
                        total.append(what);
                        total.append(")");
                    } else {
                        total.append(".appendLit(");
                        whatPos = total.length();
                        total.append(what);
                        total.append(")");
                    }
                }
            }
            first = false;
            return whatPos;
        }

        private void appendString(String str, boolean single) throws ParseException {
            if (str.length() > 0) {
                List<String> usedParameters = new ArrayList<String>();
                String parsed = QueryParser.getParameters(str, usedParameters);
                String sql = QueryReplacer.escape(parsed);
                if (usedParameters.size() > 0 && !onlySql) {
                    StringBuilder params = new StringBuilder();
                    boolean first = true;
                    List<ParamInfo> paramPositions = new ArrayList<ParamInfo>();
                    for (String parameter : usedParameters) {
                        if (first) {
                            first = false;
                        } else {
                            params.append(", ");
                        }
                        String id = location + "." + parameter;
                        parameters.add(id);
                        String expr;
                        boolean out;
                        if (parameter.startsWith(">")) {
                            if (allowOutParams) {
                                expr = parameter.substring(1);
                                out = true;
                            } else {
                                throw new ParseException("OUT parameters are not allowed for PreparedStatements", location);
                            }
                        } else {
                            expr = parameter;
                            out = false;
                        }
                        String pv = (out ? "outP" : "inP") + "(" + expr + ", \"" + id + "\")";
                        paramPositions.add(new ParamInfo(params.length(), id, expr, pv, out));
                        params.append(pv);
                    }
                    String qsql = "\"" + sql + "\", ";
                    String predParams;
                    String postParams;
                    if (single) {
                        predParams = qsql;
                        postParams = "";
                    } else {
                        predParams = "createQueryPiece(" + qsql;
                        postParams = ")";
                    }
                    int ppos = append1(predParams + params + postParams, single);
                    for (ParamInfo pos : paramPositions) {
                        String id = pos.id;
                        String pv = pos.programString;
                        int from = pred.length() + ppos + predParams.length() + pos.position + 1;
                        int to = from + pv.length();
                        ParamCutPaste cp = new ParamCutPaste(from, to, pos.expression, pos.out);
                        cp.replaceTo = pv;
                        pieces.add(cp);
                        List<ParamCutPaste> list = bindMap.get(id);
                        if (list == null) {
                            list = new ArrayList<ParamCutPaste>();
                            bindMap.put(id, list);
                        }
                        list.add(cp);
                    }
                } else {
                    append1("\"" + sql + "\"", single);
                }
            }
        }

        private BindVarCutPaste getStatementCutPaste(int from, int to, List<Pair> pairs, String rest) throws ParseException {
            if (pairs.isEmpty()) {
                if (rest.length() <= 0) {
                    total.append("\"\"");
                } else {
                    appendString(rest, true);
                }
            } else {
                for (Pair pair : pairs) {
                    appendString(pair.piece, false);
                    append1(pair.ident, false);
                }
                appendString(rest, false);
                if (!onlySql) {
                    total.append(".toQuery()");
                }
            }
            return new BindVarCutPaste(from, to, pred + total + (onlySql ? "" : ")"), pieces);
        }
    }
}
