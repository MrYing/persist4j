package cn.devezhao.persist4j.dialect.editor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import cn.devezhao.persist4j.dialect.FieldType;

/**
 * 整数
 * 
 * @author <a href="mailto:zhaofang123@gmail.com">FANGFANG ZHAO</a>
 * @since 0.1, Feb 12, 2009
 * @version $Id: IntEditor.java 121 2016-01-08 04:07:07Z zhaoff@wisecrm.com $
 */
public class IntEditor extends AbstractFieldEditor {

	private static final long serialVersionUID = 2845210116784771583L;

	public int getType() {
		return FieldType.INT.getMask();
	}

	@Override
	public void set(PreparedStatement pstmt, int index, Object value)
			throws SQLException {
		pstmt.setInt(index, (Integer) value);
	}
	
	@Override
	public Object get(ResultSet rs, int index) throws SQLException {
		return rs.getInt(index);
	}
}