package com.example.schedule_manager.domain.mandalart.entity;

import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "mandalart_cells",
        uniqueConstraints = @UniqueConstraint(columnNames = {"mandalart_board_id", "row_index", "col_index"})
)
public class MandalartCell extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mandalart_cell_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mandalart_board_id", nullable = false)
    private MandalartBoard board;

    // 보드 하나당 9x9 = 81개 셀이 row/col 0~8 좌표로 고정 생성된다 (MandalartService.createBoard 참고).
    // 좌표는 생성 후 불변 — update() 는 content 만 바꾼다
    @Column(name = "row_index", nullable = false)
    private int rowIndex;

    @Column(name = "col_index", nullable = false)
    private int colIndex;

    private String content;

    public void update(String content) {
        this.content = content;
    }
}
