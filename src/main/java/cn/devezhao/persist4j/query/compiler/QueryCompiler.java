package cn.devezhao.persist4j.query.compiler;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import antlr.collections.AST;
import cn.devezhao.persist4j.Entity;
import cn.devezhao.persist4j.Field;
import cn.devezhao.persist4j.Filter;
import cn.devezhao.persist4j.dialect.Dialect;
import cn.devezhao.persist4j.dialect.FieldType;
import cn.devezhao.persist4j.engine.SqlExecutorContext;
import cn.devezhao.persist4j.query.compiler.JoinTree.JoinNode;
import cn.devezhao.persist4j.query.compiler.antlr.AjQLParser;
import cn.devezhao.persist4j.query.compiler.antlr.AjQLParserTokenTypes;
import cn.devezhao.persist4j.query.compiler.antlr.ParserException;
import cn.devezhao.persist4j.query.compiler.antlr.ParserHelper;

/**
 * AJQL 编译器
 * 
 * @author <a href="mailto:zhaofang123@gmail.com">FANGFANG ZHAO</a>
 * @since 0.1, Jan 22, 2009
 * @version $Id: QueryCompiler.java 8 2015-06-08 09:09:03Z zhaoff@wisecrm.com $
 */
public class QueryCompiler implements Serializable {
	private static final long serialVersionUID = -47840212541257542L;

	private static final Log LOG = LogFactory.getLog(QueryCompiler.class);
	
	public static final char NAME_FIELD_PREFIX = '&';
	public static final char IDLABEL_FIELD_PREFIX = '#';  //  NOTE reserved
	public static final char CUSTOM_FUNC_PREFIX = '$';
	public static final char FORCE_JOIN_PREFIX 	= '^';
	
	public static final char NAMED_PARAM = ':';
	public static final char INDEX_PARAM = '?';
	
	private String ajql;
	private List<SelectItem> selectList = new LinkedList<SelectItem>();
	private String compiledSql = null;
	private Entity rootEntity;
	
	final private Map<String, JoinField> joinFieldMap = new HashMap<String, JoinField>();
	
	final private Map<String, ParameterItem> parameters = new HashMap<String, ParameterItem>();
	
	private SqlExecutorContext sqlExecutorContext;
	
	private int nesteTableIncrease = 0;
	private NesteSelectContext nesteSelectContext;

	/**
	 * @param ajql
	 */
	public QueryCompiler(String ajql) {
		this.ajql = ajql;
	}
	
	/**
	 * @param context
	 * @return
	 * @throws CompileException
	 */
	public String compile(SqlExecutorContext context) throws CompileException {
		return compile(context, null);
	}
	
	/**
	 * 编译SQL
	 * 
	 * @param context
	 * @param filter
	 * @return
	 */
	public String compile(SqlExecutorContext context, Filter filter) throws CompileException {
		if (compiledSql != null) {
			return compiledSql;
		}
		
		this.sqlExecutorContext = context;
		
		AjQLParser parser = ParserHelper.createAjQLParser(ajql, true);
		try {
			parser.statement();
		} catch (Exception ex) {
			handleParseException(ex);
		}
		compiledSql = compileQuery( parser.getAST(), filter );
		return compiledSql;
	}
	
	/**
	 * 获取查询主实体
	 * 
	 * @return
	 */
	public Entity getRootEntity() {
		throwIfUncompile();
		return rootEntity;
	}
	
	/**
	 * 获取查询项（字段）
	 * 
	 * @return
	 * @throws IllegalStateException
	 */
	public SelectItem[] getSelectItems() throws IllegalStateException {
		throwIfUncompile();
		return selectList.toArray(new SelectItem[selectList.size()]);
	}
	
	/**
	 * 获取编译好的 SQL
	 * 
	 * @return
	 * @throws IllegalStateException
	 */
	public String getCompiledSql() throws IllegalStateException {
		throwIfUncompile();
		return compiledSql;
	}
	
	/**
	 * 获取入参
	 * 
	 * @return
	 * @throws IllegalStateException
	 */
	public Map<String, ParameterItem> getParameters() throws IllegalStateException {
		throwIfUncompile();
		return parameters;
	}
	
	// ----------------------------------------------------------- Compile
	
	/**
	 * 主编译方法
	 * 
	 * @param select
	 * @param filter
	 * @return
	 */
	private String compileQuery(AST select, Filter filter) {
		AST from = select.getNextSibling();
		if (LOG.isDebugEnabled()) {
			LOG.debug("compile select clause [" + select.toStringList() + " ]");
		}
		
		Entity entity = sqlExecutorContext.getEntity(from.getFirstChild().getText());
		this.rootEntity = entity;
		final JoinTree aJTree = new JoinTree(entity.getPhysicalName(),
				nesteSelectContext == null ? -1 : nesteSelectContext.getTableIncrease() /* in neste-sql */);
		
		boolean distinctField = false;
		Set<JoinField> distinctFields = new HashSet<JoinField>();
		
		List<JoinField> selectFields = new LinkedList<JoinField>();
		AST item = select.getFirstChild();
		do {
			JoinField aJF = null;
			if (item.getType() == AjQLParserTokenTypes.DISTINCT) {
				distinctField = true;
				continue;
			}
			
			if (ParserHelper.isAggregator(item.getType())) {
				AST column = item.getFirstChild();
				aJF = bindJoinField(aJTree, entity, column, SelectItemType.Aggregator);
				
				aJF.setAggregator(item.getText());
				if (ParserHelper.hasAggregatorMode(item.getType())) {
					String mode = column.getNextSibling().getNextSibling().getText();  // [, '%Y']
					aJF.setAggregatorMode(mode);
				}
			} else {
				aJF = bindJoinField(aJTree, entity, item, SelectItemType.Field);
			}
			
			selectFields.add(aJF);
			if (distinctField) {
				distinctFields.add(aJF);
			}
			distinctField = false;
		} while ((item = item.getNextSibling()) != null);
		selectList.clear();
		selectList.addAll(selectFields);
		
		// fields in where and group-by and having and order-by 
		AST where = null, group = null, having = null, order = null;
		AST next = from.getNextSibling();
		while (next != null) {
			switch (next.getType()) {
			case AjQLParserTokenTypes.WHERE:
				where = next;
				break;
			case AjQLParserTokenTypes.GROUP:
				group = next;
				break;
			case AjQLParserTokenTypes.HAVING:
				having = next;
				break;
			case AjQLParserTokenTypes.ORDER:
				order = next;
				break;
			}
			
			findJoinFields(next, aJTree, entity);
			next = next.getNextSibling();
		}
		
		if (filter != null) {
			String fstrs = filter.evaluate(this.rootEntity);
			// patch #whereClause()
			fstrs = "where " + ( (where == null) ? fstrs : "1=1 and " + fstrs );
				
			AjQLParser parser = ParserHelper.createAjQLParser(fstrs);
			try {
				parser.whereClause();
			} catch (Exception ex) {
				handleParseException(ex);
			}
			
			AST nood = parser.getAST();
			findJoinFields(nood, aJTree, entity);
			
			if (where == null) {
				where = nood;
			} else {
				// remove 1=1
				AST and = nood.getFirstChild().getNextSibling().getNextSibling().getNextSibling();  // 1 -> = -> 1 -> AND
				where.addChild(and);
			}
		}
		
		StringBuilder sql = new StringBuilder("select ");
		// generate join clause and table alias
		String aJQL = aJTree.toJoinsSQL(sqlExecutorContext.getDialect());
		
		int columnIncrease = 0;
		Iterator<JoinField> iter = selectFields.iterator();
		for (;; columnIncrease++) {
			JoinField aJF = iter.next();
			String clause = null;
			
			switch (aJF.getType()) {
			case Aggregator:
				aJF.as(columnIncrease, sqlExecutorContext.getDialect());
				if (aJF.getAggregatorMode() != null) {
					clause = aJF.getAggregator() + "( " + aJF.getName() + ", '" + aJF.getAggregatorMode() + "' ) as ";
				} else {
					clause = aJF.getAggregator() + "( " + aJF.getName() + " ) as ";
				}
				clause += JoinTree.COLUMN_ALIAS_PREFIX + columnIncrease;
				break;
			default:
				clause = aJF.as(columnIncrease, sqlExecutorContext.getDialect());
				break;
			}
			
			if (distinctFields.contains(aJF)) {
				sql.append("distinct ");
			}
			sql.append(clause);
			if (iter.hasNext()) {
				sql.append(", ");
			} else {
				break;
			}
		}
		sql.append(" from ").append(aJQL).append(" where ");
		
		StringBuilder clause = new StringBuilder();
		if (where == null) {
			sql.append("( 1 = 1 ) ");
		} else {
			int paramIndex = 0;
			JoinField aJF = null;
			
			next = where.getFirstChild();
			int prevType = 0;
			AST lastField = null;
			do {
				int type = next.getType();
				String text = next.getText();
				
				if (next.getNextSibling() != null && next.getNextSibling().getType() == AjQLParserTokenTypes.MATCH) {
					lastField = next;
					continue;
				}
				
				switch (type) {
				case AjQLParserTokenTypes.IDENT:
					aJF = getJoinField(next, null, sqlExecutorContext.getDialect());
					clause.append(aJF.getName());
					break;
				case AjQLParserTokenTypes.QUOTED_STRING:
					clause.append('\'').append(text).append('\'');
					break;
				case AjQLParserTokenTypes.IN:
					clause.append(compileInClause(next));
					break;
				case AjQLParserTokenTypes.EXISTS:
					clause.append(compileExistsClause(next, aJTree.getRootJoinNode()));
					break;
				case AjQLParserTokenTypes.QUESTION_MARK:
				case AjQLParserTokenTypes.NAMED_PARAM:
					clause.append('?');
					paramIndex++;
					text = (text.charAt(0) == '?') ? paramIndex + "" : text;
					parameters.put( text, new ParameterItem(text, paramIndex, aJF.getField()) );
					break;
				case AjQLParserTokenTypes.MATCH:
					clause.append(compileMatchClause(next, lastField, aJTree.getRootJoinNode()));
					lastField = null;
					next = next.getNextSibling();
					break;
				case AjQLParserTokenTypes.BAND: 
				case AjQLParserTokenTypes.NBAND: 
					clause.append('&');
					break;
				default:
					if (ParserHelper.isInIgnore(type)) {
						clause.append(text);
						
						// 按位取反 a % b = 0
						if (prevType == AjQLParserTokenTypes.NBAND) {
							clause.append(" = 0");
						}
					} else {
						LOG.warn("Unknow token: <" + type + ", " + text + ">");
					}
				}
				
				clause.append(' ');
				prevType = type;
			} while (next != null && (next = next.getNextSibling()) != null);
			
			sql.append(clause);
			clause = null;
		}
		
		if (group != null) {
			sql.append(compileGroupByClause(group, having));
		}
		
		if (order != null) {
			sql.append(compileOrderByClause(order));
		}
		
		return sql.toString().trim();
	}
	
	/**
	 * 编译 IN 子语句
	 * 
	 * @param in
	 * @return
	 */
	private String compileInClause(AST in) {
		AST next = in.getFirstChild();
		StringBuilder clause = new StringBuilder();
		JoinField aJF = getJoinField(next, null, sqlExecutorContext.getDialect());
		clause.append(aJF.getName());
		if (next.getNextSibling().getType() == AjQLParserTokenTypes.NOT) {
			clause.append(" not");
			next = next.getNextSibling();
		}
		clause.append(" in ( ");
		
		while ((next = next.getNextSibling()) != null) {
			String text = next.getText();
			int ttype = next.getType();
			switch (ttype) {
			case AjQLParserTokenTypes.QUOTED_STRING:
				clause.append('\'').append(text).append('\'');
				break;
			case AjQLParserTokenTypes.SELECT:
				String neste = new QueryCompiler(this.sqlExecutorContext, null).compileNesteSelect(next);
				if (LOG.isDebugEnabled()) {
					LOG.debug("compiled neste select clause [ " + neste + " ], will return now");
				}
				return clause.append(neste).append(" )").toString();  // NOTE: neste select, return now
			default:
				if (ParserHelper.isInIgnoreValue(ttype) || ParserHelper.isInIgnore(ttype)) {
					clause.append(text);
				} else {
					LOG.warn("Unknow token in in clause [ " + ttype + ":" + text + " ]");
				}
				break;
			}

			if (ttype == AjQLParserTokenTypes.COMMA) {
				clause.append(' ');
			}
		}
		return clause.append(" )").toString();
	}
	
	/**
	 * 编译 EXISTS 子语句
	 * @param exists
	 * @param root
	 * @return
	 */
	private String compileExistsClause(AST exists, JoinNode root) {
		StringBuilder clause = new StringBuilder();
		clause.append("exists ( ");
		
		AST next = exists.getFirstChild();
		do {
			String text = next.getText();
			int ttype = next.getType();
			switch (ttype) {
			case AjQLParserTokenTypes.QUOTED_STRING:
				clause.append('\'').append(text).append('\'');
				break;
			case AjQLParserTokenTypes.SELECT:
				NesteSelectContext context = new NesteSelectContext(rootEntity, root, this.nesteTableIncrease++);
				String neste = new QueryCompiler(this.sqlExecutorContext, context).compileNesteSelect(next);
				if (LOG.isDebugEnabled()) {
					LOG.debug("compiled neste select clause [ " + neste + " ], will return now");
				}
				return clause.append(neste).append(" )").toString();  // NOTE: neste select, return now
			default:
				if (ParserHelper.isInIgnoreValue(ttype) || ParserHelper.isInIgnore(ttype)) {
					clause.append(text);
				} else {
					LOG.warn("Unknow token in exists clause [ " + ttype + ":" + text + " ]");
				}
				break;
			}
			
			if (ttype == AjQLParserTokenTypes.COMMA) {
				clause.append(' ');
			}
		} while ((next = next.getNextSibling()) != null);
		return clause.append(" )").toString();
	}
	
	/**
	 * 编译 GROUP BY 子语句
	 * 
	 * @param by
	 * @param having
	 * @return
	 */
	private String compileGroupByClause(AST by, AST having) {
		StringBuilder clause = new StringBuilder();
		clause.append( compileByClause(by, "group by ") );
		AST with = null;
		if (having != null) {
			clause/*.append(' ')*/.append( compileByClause(having, "having ") );
			with = having.getNextSibling();
		} else {
			with = by.getNextSibling();
		}
		
		if (with != null && with.getType() == AjQLParserTokenTypes.WITH) {
			AST rollup = with.getFirstChild();
			if (rollup != null && rollup.getType() == AjQLParserTokenTypes.ROLLUP) {
				clause.append("with rollup ");
			}
		}
		
		return clause.toString();
	}
	
	/**
	 * 编译 ORDER BY 子语句
	 * 
	 * @param by
	 * @return
	 */
	private String compileOrderByClause(AST by) {
		return compileByClause(by, "order by ").toString();
	}
	
	/**
	 * 编译 [ORDER|GROYP] BY 子语句
	 * 
	 * @param ast
	 * @param who
	 * @return
	 */
	private StringBuilder compileByClause(AST ast, String who) {
		StringBuilder clause = new StringBuilder(who);
		
		AST next = null;
		if (who.startsWith("having")) {
			next = ast.getFirstChild();
		} else {
			next = ast.getFirstChild().getNextSibling();
		}
		
		do {
			int ttype = next.getType();
			JoinField aJF = null;
			switch (ttype) {
			case AjQLParserTokenTypes.COMMA:
				clause.insert(clause.length() - 1, ',');
				break;
			case AjQLParserTokenTypes.IDENT:
				aJF = getJoinField(next, null, sqlExecutorContext.getDialect());
				clause.append(aJF.getName()).append(' ');
				break;
			case AjQLParserTokenTypes.ASC:
			case AjQLParserTokenTypes.DESC:  // Only for order
				clause.append(next.getText()).append(' ');
				break;
			default:
				if (ParserHelper.isAggregator(ttype)) {  // Only for group-by
					AST item = next.getFirstChild();
					aJF = getJoinField(item, next, sqlExecutorContext.getDialect());
					clause.append(next.getText()).append("( ");
					if (aJF.getAggregatorMode() != null) {
						clause.append(aJF.getName()).append(", '").append(aJF.getAggregatorMode()).append("' ) ");
					} else {
						clause.append(aJF.getName()).append(" ) ");
					}
				} 
				else {  /*if (ParserLeader.isInIgnoreValue(ttype) || ParserLeader.isInIgnore(ttype))*/  // for others
					clause.append(next.getText()).append(' ');
				}
			}
		} while ((next = next.getNextSibling()) != null);
		return clause;
	}
	
	/**
	 * 编译 MATCH ... AGAINST
	 * 
	 * @param match
	 * @param field
	 * @param root
	 * @return
	 */
	private String compileMatchClause(AST match, AST field, JoinNode root) {
		JoinField aJF = getJoinField(field, null, sqlExecutorContext.getDialect());
		AST query = match.getNextSibling();
		return String.format("match (%s) against ('%s' in boolean mode)",
				aJF.getName(),
				query.getText());
	}
	
	/**
	 * 获取连接（join）字段
	 * 
	 * @param tree
	 * @param entity
	 * @param item
	 * @param type
	 * @return
	 */
	private JoinField bindJoinField(JoinTree tree, Entity entity, AST item, SelectItemType type) {
		final String itemName = item.getText();
		JoinField ifExists = joinFieldMap.get(itemName);
		if (ifExists != null) {
			JoinField clone = new JoinField(ifExists, type);
			return clone;
		}
		
		String path = itemName;
		if (path.charAt(0) == NAME_FIELD_PREFIX) {  // eg. &accountId
			type = SelectItemType.NameField;
			path = path.substring(1);
			
			String[] joined = path.split("\\.");
			path += '.';
			Entity currentEntity = entity;
			for (int i = 0; i < joined.length; i++) {
				Field field = currentEntity.getField(joined[i]);
				currentEntity = getReferenceEntity(field);
				if (i + 1 == joined.length) {
					path += currentEntity.getNameField().getName();
					break;
				}
			}
		}
		
		String[] joined = path.split("\\.");
		
		if (path.charAt(0) == FORCE_JOIN_PREFIX) {  // eg. ^SalesOrder.totalAmount , ^t2Reference
			if (joined.length == 2) {
				Entity joinEntity = sqlExecutorContext.getEntity( joined[0].substring(1) );
				Field referenceTo = null;
				for (Field to : rootEntity.getReferenceToFields()) {
					if ( joinEntity.equals(to.getOwnEntity()) ) {
						referenceTo = to;
						break;
					}
				}
				
				if (referenceTo == null) {
					throw new CompileException("entity " + joinEntity.getName() + " no reference-to " + rootEntity.getName());
				}
				
				JoinNode jNode = tree.addChildJoin(
						joinEntity.getPhysicalName(), rootEntity.getPrimaryField().getPhysicalName(), referenceTo.getPhysicalName());
				JoinField aJF = new JoinField(jNode, joinEntity.getField(joined[1]), itemName, type);
				joinFieldMap.put(itemName, aJF);
				return aJF;
			} else if (joined.length == 1 && nesteSelectContext != null) {  // in neste-sql (exists)
				Entity master = nesteSelectContext.getMaster();
				JoinField aJF = new JoinField(
						nesteSelectContext.getMasterRoot(), master.getField(joined[0].substring(1)), itemName, type);
				joinFieldMap.put(itemName, aJF);
				return aJF;
			}
			throw new CompileException("Force join must has two nodes");
		}
		
		if (joined.length == 1) {  // No joins
			Field field = entity.getField(joined[0]);
			Validate.notNull(field, "Unknow field [ " + joined[0] + " ] in entity [ " + entity.getName() + " ]");
			JoinField aJF = new JoinField(tree.getRootJoinNode(), field, itemName, type);
			joinFieldMap.put(itemName, aJF);
			return aJF;
		}
		
		Field crtf = null;
		Entity crte = entity;
		JoinNode pjn = null;  // previous JoinNode
		for (int i = 0; i < joined.length; i++) {  // eg. accountId.ownUser.fullName
			String fn = joined[i];
			crtf = crte.getField(fn);
			Validate.notNull(crtf, "Unknow field [ " + fn + " ] in entity [ " + crte.getName() + " ]");
			crte = getReferenceEntity(crtf);
			
			if (pjn == null) {
				pjn = tree.addChildJoin(
						crte.getPhysicalName(), crtf.getPhysicalName(), crte.getPrimaryField().getPhysicalName());
			} else {
				pjn = tree.addChildJoin(
						crte.getPhysicalName(), crtf.getPhysicalName(), crte.getPrimaryField().getPhysicalName(), 
						pjn);
			}
			
			if (i + 2 == joined.length) {
				Field last = crte.getField(joined[i + 1]);
				Validate.notNull(last, "Unknow field [ " + fn + " ] in entity [ " + crte.getName() + " ]");
				
				JoinField aJF = new JoinField(pjn, last, itemName, type);
				joinFieldMap.put(itemName, aJF);
				return aJF;
			}
		}
		throw new CompileException("Unknow error on bind JoinField");
	}
	
	/**
	 * 获取引用字段的引用实体
	 * 
	 * @param field
	 * @return
	 */
	private Entity getReferenceEntity(Field field) {
		if (FieldType.REFERENCE != field.getType()) {
			throw new CompileException(field + " does not support joins");
		}
		return field.getReferenceEntity();
	}
	
	/**
	 * @param ast
	 * @param aJTree
	 * @param entity
	 */
	private void findJoinFields(AST ast, JoinTree aJTree, Entity entity) {
		if (ast == null) {
			return;
		}
		
		ast = ast.getFirstChild();
		do {
			AST node = ast;
			if (node.getType() != AjQLParserTokenTypes.IDENT) {
				node = node.getFirstChild();
				if (node == null || node.getType() != AjQLParserTokenTypes.IDENT) {
					continue;
				}
			}
			bindJoinField(aJTree, entity, node, SelectItemType.Field);
			
		} while ((ast = ast.getNextSibling()) != null);
	}
	
	/**
	 * @param item
	 * @param aggregator
	 * @param dialect
	 * @return
	 */
	private JoinField getJoinField(AST item, AST aggregator, Dialect dialect) {
		JoinField aJF = joinFieldMap.get(item.getText());
		if (aJF == null) {
			throw new CompileException("Unknow JoinField in clause [ " + item.getType() + ", " + item.getText() + " ]");
		}
		
		// Use clone
		JoinField clone = new JoinField(aJF, null);
		if (aggregator != null) {
			clone.setAggregator(aggregator.getText());
			if (ParserHelper.hasAggregatorMode(aggregator.getType())) {
				String mode = item.getNextSibling().getNextSibling().getText();  // [, '%Y']
				clone.setAggregatorMode(mode);
			} else {
				clone.setAggregatorMode(null);
			}
		} else {
			clone.setAggregator(null);
			clone.setAggregatorMode(null);
		}
		clone.as(-1, dialect);  // compile
		return clone;
	}
	
	/**
	 */
	private void throwIfUncompile() {
		if (this.compiledSql == null) {
			throw new IllegalStateException("uncompile");
		}
	}
	
	/**
	 * @param ex
	 */
	private void handleParseException(Exception ex) {
		if (ex instanceof ParserException) {
			Throwable cause = ex.getCause();
			throw new CompileException(
					"ANTLR can't parse AjQL stream, threw an exception [ "
					+ cause.getClass().getName() + ": " + ex.getCause() + " ]", ex.getCause());
		}
		throw new CompileException("Parse AjQL error", ex);
	}
	
	// ----------------------------------------------------------- Just for Neste SQL
	
	private QueryCompiler(SqlExecutorContext context, NesteSelectContext nesteSelectContext) {
		this.sqlExecutorContext = context;
		this.nesteSelectContext = nesteSelectContext;
	}
	
	private String compileNesteSelect(AST nesteSelect) {
		return compileQuery(nesteSelect, null);
	}
}
