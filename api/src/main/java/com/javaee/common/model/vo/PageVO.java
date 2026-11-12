package com.javaee.common.model.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * @author qxk
 * @description: 分页响应VO（统一）
 */
@Data
@NoArgsConstructor
public class PageVO<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    // 数据列表
    private List<T> list;

    // 总记录数
    private Long total;

    // 页码
    private Integer pageNum;

    // 每页大小
    private Integer pageSize;

    // 总页数
    private Integer pages;

    // 是否有上一页
    private Boolean hasPreviousPage;

    // 是否有下一页
    private Boolean hasNextPage;

    public PageVO(List<T> list, Long total, Integer pageNum, Integer pageSize) {
        this.list = list;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = (int) Math.ceil((double) total / pageSize);
        this.hasPreviousPage = pageNum > 1;
        this.hasNextPage = pageNum < this.pages;
    }

    public void setTotal(Long total) {
        this.total = total;
        if (pageSize != null && pageSize > 0) {
            this.pages = (int) Math.ceil((double) total / pageSize);
        }
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
        if (pages != null) {
            this.hasPreviousPage = pageNum > 1;
            this.hasNextPage = pageNum < this.pages;
        }
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
        if (total != null && total > 0) {
            this.pages = (int) Math.ceil((double) total / pageSize);
        }
        if (pageNum != null && pages != null) {
            this.hasPreviousPage = pageNum > 1;
            this.hasNextPage = pageNum < this.pages;
        }
    }

    public void setPages(Integer pages) {
        this.pages = pages;
        if (pageNum != null) {
            this.hasPreviousPage = pageNum > 1;
            this.hasNextPage = pageNum < pages;
        }
    }

    /**
     * 构建分页响应
     * @param list 数据列表
     * @param total 总记录数
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param <T> 数据类型
     * @return PageVO
     */
    public static <T> PageVO<T> build(List<T> list, Long total, Integer pageNum, Integer pageSize) {
        return new PageVO<>(list, total, pageNum, pageSize);
    }
}
